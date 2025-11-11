package eu.esa.opt.dataio.s2;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.multilevel.support.DefaultMultiLevelImage;
import com.bc.ceres.multilevel.support.DefaultMultiLevelModel;
import com.bc.ceres.multilevel.support.DefaultMultiLevelSource;
import com.bc.zarr.ArrayParams;
import com.bc.zarr.DataType;
import com.bc.zarr.ZarrArray;
import com.bc.zarr.ZarrGroup;
import com.bc.zarr.storage.FileSystemStore;
import org.esa.snap.core.dataio.AbstractProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.dataio.geocoding.*;
import org.esa.snap.core.dataio.geocoding.forward.PixelForward;
import org.esa.snap.core.dataio.geocoding.inverse.PixelQuadTreeInverse;
import org.esa.snap.core.dataio.geocoding.util.RasterUtils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.datamodel.quicklooks.Quicklook;
import org.esa.snap.core.image.ResolutionLevel;
import org.esa.snap.core.util.ISO8601Converter;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;
import ucar.ma2.InvalidRangeException;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.*;
import java.util.List;

import static eu.esa.opt.dataio.s2.S2ZarrConstants.*;
import static eu.esa.opt.dataio.s2.S2ZarrUtils.*;
import static org.esa.snap.core.util.SystemUtils.LOG;

public class S2ZarrProductReader extends AbstractProductReader {

    private Path rootPath;
    private ZarrGroup rootGroup;
    private Product product;
    private final Map<String, GeoCoding> geoCodings = new HashMap<>();
    private ColorProvider colorProvider;

    /**
     * Constructs a new abstract product reader.
     *
     * @param readerPlugIn the reader plug-in which created this reader, can be {@code null} for internal reader
     *                     implementations
     */
    protected S2ZarrProductReader(ProductReaderPlugIn readerPlugIn) {
        super(readerPlugIn);
    }

    @Override
    protected Product readProductNodesImpl() throws IOException {
        final Path inputPath = convertToPath(getInput());
        String fileName = inputPath.getFileName().toString();
        final String lowerName = fileName.toLowerCase();
        final String productType = getProductType(fileName);

        if (lowerName.endsWith(ZARR_FILE_EXTENSION)) {
            rootPath = inputPath;
            fileName = fileName.substring(0, fileName.length() - ZARR_FILE_EXTENSION.length());
        } else {
            rootPath = inputPath.getParent();
        }
        assert rootPath != null;
        FileSystemStore store = new FileSystemStore(rootPath);
        rootGroup = ZarrGroup.open(store);
        final Map<String, Object> productAttributes = rootGroup.getAttributes();

        final ProductData.UTC sensingStart = getTime(productAttributes, "start");
        final ProductData.UTC sensingStop = getTime(productAttributes, "end");

        product = new Product(fileName, productType, this);
        product.setStartTime(sensingStart);
        product.setEndTime(sensingStop);
        product.setAutoGrouping(AUTO_GROUPING);
        readMetadata();
        setSceneGeoCoding();
        initGeoCodings();
        readArraysAsBandsOrMetadata(productAttributes);
        registerRGBProfiles();
        product.setFileLocation(rootPath.toFile());
        product.setProductReader(this);
        product.setModified(false);
        return product;
    }

    private void registerRGBProfiles() {
        RGBImageProfile profile_10 = new RGBImageProfile(RGB_10M_IMAGE_PROFILE_NAME, // display name
                new String[]{
                        "b02_r10m_reflectance",  // red channel band-maths expression
                        "b03_r10m_reflectance",  // green channel band-maths expression
                        "b04_r10m_reflectance"   // blue channel band-maths expression
                });
        RGBImageProfileManager.getInstance().addProfile(profile_10);
        RGBImageProfile profile_20 = new RGBImageProfile(RGB_20M_IMAGE_PROFILE_NAME, // display name
                new String[]{
                        "b02_r20m_reflectance",  // red channel band-maths expression
                        "b03_r20m_reflectance",  // green channel band-maths expression
                        "b04_r20m_reflectance"   // blue channel band-maths expression
                });
        RGBImageProfileManager.getInstance().addProfile(profile_20);
        RGBImageProfile profile_60 = new RGBImageProfile(RGB_60M_IMAGE_PROFILE_NAME, // display name
                new String[]{
                        "b02_r60m_reflectance",  // red channel band-maths expression
                        "b03_r60m_reflectance",  // green channel band-maths expression
                        "b04_r60m_reflectance"   // blue channel band-maths expression
                });
        RGBImageProfileManager.getInstance().addProfile(profile_60);
    }

    private String getProductType(String productName) {
        String productTypePart = productName.split("_")[1];
        productTypePart = productTypePart.substring(productTypePart.length() - 2);
        return "S2_MSI_Level-" + productTypePart + "_ZARR";
    }

    @Override
    protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight, int sourceStepX, int sourceStepY, Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm) {
        throw new IllegalStateException("Data is provided by images");
    }

    @Override
    public void readBandRasterData(Band destBand, int destOffsetX, int destOffsetY, int destWidth, int destHeight, ProductData destBuffer, ProgressMonitor pm) {
        throw new IllegalStateException("Data is provided by images");
    }

    private ProductData.UTC getTime(Map<String, Object> productAttributes, String timeType) throws IOException {
        String timeAttributeName = timeType + "_datetime";
        Map<String, Object> stac_attributes = cast(productAttributes.get(STAC_DISCOVERY_ATTRIBUTES_NAME));
        Map<String, Object> propertiesAttributes = cast(stac_attributes.get(PROPERTIES_ATTRIBUTES_NAME));
        if (propertiesAttributes.containsKey(timeAttributeName)) {
            String timeString = ((Map<?, ?>) propertiesAttributes).get(timeAttributeName).toString();
            try {
                int plusIndex = timeString.indexOf("+");
                if (plusIndex == timeString.length() - 6) {
                    timeString = timeString.substring(0, plusIndex + 3) +
                            timeString.substring(plusIndex + 4);
                }
                return ISO8601Converter.parse(timeString);
            } catch (ParseException e) {
                throw new IOException(
                        "Unparseable " + timeAttributeName + " while reading product '" + rootPath.toString() + "'",
                        e
                );
            }
        }
        return null;
    }

    private String getBandName(String[] splitArrayKey) {
        String origBandName = splitArrayKey[splitArrayKey.length - 1];
        String parentFolder = splitArrayKey[splitArrayKey.length - 2];
        String bandName;
        if (RESOLUTIONS.contains(parentFolder)) {
            String grandParentFolder = splitArrayKey[splitArrayKey.length - 3];
            bandName = origBandName + "_" + parentFolder + "_" + grandParentFolder;
        } else {
            bandName = origBandName + "_" + parentFolder;
        }
        return bandName;
    }

    private void readMetadata() throws IOException {
        Map<String, Object> rootAttributes = rootGroup.getAttributes();
        addToMetadataElement(product.getMetadataRoot(), rootAttributes);
    }

    private void addToMetadataElement(MetadataElement element, Map<String, Object> attributes) {
        Map<String, String> metadataSplitter = Map.of(
                "spectral_response_values", " ", "product_quality_status", ",",
                "list of used processing parameters file names", ","
        );
        for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
            if (metadataSplitter.containsKey(attribute.getKey())) {
                String[] valueList = attribute.getValue().toString().split(metadataSplitter.get(attribute.getKey()));
                Map<String, Object> valueMap = new LinkedHashMap<>();
                for (int i = 0; i < valueList.length; i++) {
                    valueMap.put("" + i, valueList[i]);
                }
                MetadataElement newElement = new MetadataElement(attribute.getKey());
                addToMetadataElement(newElement, valueMap);
                element.addElement(newElement);
            } else if (attribute.getValue() instanceof Map) {
                MetadataElement newElement = new MetadataElement(attribute.getKey());
                addToMetadataElement(newElement, (Map<String, Object>) attribute.getValue());
                element.addElement(newElement);
            } else if (attribute.getValue() instanceof ArrayList<?>) {
                ArrayList<?> valueList = (ArrayList<?>) attribute.getValue();
                HashMap<String, Object> valueMap = new LinkedHashMap<>();
                for (int i = 0; i < valueList.size(); i++) {
                    valueMap.put("" + i, valueList.get(i));
                }
                MetadataElement newElement = new MetadataElement(attribute.getKey());
                addToMetadataElement(newElement, valueMap);
                element.addElement(newElement);
            } else {
                MetadataAttribute metadataAttribute = createMetadataAttribute(attribute.getKey(), attribute.getValue());
                element.addAttribute(metadataAttribute);
            }
        }
    }

    private MetadataAttribute createMetadataAttribute(String key, Object attributeValue) {
        ProductData productData = ProductData.createInstance(ProductData.TYPE_ASCII, attributeValue.toString());
        return new MetadataAttribute(key, productData, false);
    }

    private void initGeoCodings() throws IOException {
        List<String>[] coordinatePairs = new List[]{
                new ArrayList<>(List.of("x", "y")),
                new ArrayList<>(List.of("longitude", "latitude"))
        };
        for (String arrayKey : rootGroup.getArrayKeys()) {
            ZarrArray array;
            try {
                array = rootGroup.openArray(arrayKey);
            } catch (IllegalArgumentException iae) {
                LOG.warning("Could not read array '" + arrayKey + "'");
                continue;
            }
            Map<String, Object> arrayAttributes = array.getAttributes();
            if (arrayAttributes.containsKey(ARRAY_DIMENSIONS_ATTRIBUTES_NAME)) {
                List<String> dimensionList = cast(arrayAttributes.get(ARRAY_DIMENSIONS_ATTRIBUTES_NAME));
                for (List<String> coordinatePair : coordinatePairs) {
                    if (new HashSet<>(dimensionList).containsAll(coordinatePair)) {
                        try {
                            initGeoCoding(array, arrayAttributes, arrayKey, coordinatePair);
                        } catch (FactoryException | TransformException | InvalidRangeException e) {
                            throw new IOException("Cannot initialise geocoding", e);
                        }
                    }
                }
            }
        }
    }

    private void setSceneGeoCoding() throws IOException {
        Map<String, Object> stac_map;
        try {
            stac_map = S2ZarrUtils.cast(rootGroup.getAttributes().get(S2ZarrConstants.STAC_DISCOVERY_ATTRIBUTES_NAME));
            Map<String, Object> propertiesMap = cast(stac_map.get(PROPERTIES_ATTRIBUTES_NAME));
            int epsg_code = cast(propertiesMap.get(EPSG_ATTRIBUTES_NAME));
            CoordinateReferenceSystem crs = CRS.decode("EPSG:" + epsg_code);
            ArrayList<Double> bbox  = cast(propertiesMap.get(BBOX_ATTRIBUTES_NAME));
            double easting = bbox.get(0);
            double northing = bbox.get(3);
            GeoCoding geoCoding = new CrsGeoCoding(
                    crs, 109800, 109800, easting, northing, 1.0, 1.0, 0.0, 0.0
            );
            product.setSceneGeoCoding(geoCoding);
        } catch (FactoryException | TransformException e) {
            throw new IOException("Cannot initialise product geocoding", e);
        }
    }

    private void readArraysAsBandsOrMetadata(Map<String, Object> productAttributes) throws IOException {
        ArrayList<String> notMetadata = new ArrayList<>(List.of("x", "y", "longitude", "latitude", "detector", "band"));
        List<String>[] coordinatePairs = new List[]{
                new ArrayList<>(List.of("x", "y")),
                new ArrayList<>(List.of("longitude", "latitude"))
        };
        for (String arrayKey : rootGroup.getArrayKeys()) {
            ZarrArray array;
            try {
                array = rootGroup.openArray(arrayKey);
            } catch (IllegalArgumentException iae) {
                LOG.warning("Could not read array '" + arrayKey + "'");
                continue;
            }
            String[] splitArrayKey = arrayKey.split("/");
            String origBandName = splitArrayKey[splitArrayKey.length - 1];
            String bandName = getBandName(splitArrayKey);
            Map<?, ?> bandDescription = getBandDescription(productAttributes, origBandName);
            Map<String, Object> arrayAttributes = array.getAttributes();
            boolean bandSet = false;
            List<String> dimensionList = cast(arrayAttributes.get(ARRAY_DIMENSIONS_ATTRIBUTES_NAME));
            for (List<String> coordinatePair : coordinatePairs) {
                if (dimensionList != null && new HashSet<>(dimensionList).containsAll(coordinatePair)) {
                    int[] shape = array.getShape();
                    Band band;
                    if (shape.length == 2) {
                        band = createBand(bandName, array, new int[0]);
                        applyBandAttributes(band, bandDescription, arrayAttributes);
                        addFlagCoding(band, arrayAttributes);
                        addIndexCoding(band);
                        addMasks(band);
                        bandSet = true;
                    } else {
                        String[][] bandNameParts = BAND_NAME_PARTS.get(origBandName);
                        int numDims = shape.length - 2;
                        int[] additionalIndexes = new int[numDims];
                        ArrayList<Band> resultingBands = new ArrayList<>();
                        while (additionalIndexes[0] < shape[0]) {
                            StringBuilder subDimBandNameBuilder = new StringBuilder();
                            for (int i = 0; i < numDims; i++) {
                                subDimBandNameBuilder.append(bandNameParts[i][additionalIndexes[i]]).append("_");
                            }
                            String subDimBandName = subDimBandNameBuilder.toString();
                            subDimBandName += BASE_NAMES.getOrDefault(origBandName, bandName);
                            band = createBand(subDimBandName, array, additionalIndexes.clone());
                            applyBandAttributes(band, bandDescription, arrayAttributes);
                            addFlagCoding(band, arrayAttributes);
                            addIndexCoding(band);
                            addMasks(band);
                            resultingBands.add(band);
                            int currentDim = numDims - 1;
                            while (currentDim >= 0) {
                                additionalIndexes[currentDim]++;
                                if (currentDim > 0) {
                                    additionalIndexes[currentDim] %= shape[currentDim];
                                }
                                if (additionalIndexes[currentDim] > 0) {
                                    break;
                                }
                                currentDim--;
                            }
                        }
                        if (bandName.contains(QUICKLOOK_BAND_NAME)) {
                            Band[] quicklookBands = resultingBands.toArray(new Band[0]);
                            Quicklook quicklook = new Quicklook(product, bandName, quicklookBands);
                            product.getQuicklookGroup().add(quicklook);
                        }
                        bandSet = true;
                    }
                }
            }
            if (!bandSet) {
                if (notMetadata.contains(origBandName)) {
                    continue;
                }
                // read as metadata
                ProductData productData = null;
                try {
                    productData = getProductDataFromKey(arrayKey, "");
                } catch (InvalidRangeException e) {
                    LOG.warning("Cannot read '" + origBandName + "' as metadata.");
                }
                String parentName = splitArrayKey[splitArrayKey.length - 2];
                int[] arrayShape = array.getShape();
                if (!product.getMetadataRoot().containsElement(parentName)) {
                    product.getMetadataRoot().addElement(new MetadataElement(parentName));
                }
                MetadataElement parentElement = product.getMetadataRoot().getElement(parentName);
                if (arrayShape.length == 1 && arrayShape[0] == 1) {
                    MetadataAttribute metadataAttribute = createMetadataAttribute(
                            origBandName, productData.getElemStringAt(0)
                    );
                    parentElement.addAttribute(metadataAttribute);
                    continue;
                }
                MetadataElement element = new MetadataElement(origBandName);
                int count = 0;
                for (int i = 0; i < arrayShape[0]; i++) {
                    String[] dimensionNames = null;
                    if (dimensionList != null && DIMENSION_NAMES.containsKey(dimensionList.get(0))) {
                        dimensionNames = DIMENSION_NAMES.get(dimensionList.get(0));
                    }
                    if (arrayShape.length > 1) {
                        MetadataElement innerElement = new MetadataElement(dimensionNames[i]);
                        String[] innerDimensionNames = null;
                        if (dimensionList != null && DIMENSION_NAMES.containsKey(dimensionList.get(1))) {
                            innerDimensionNames = DIMENSION_NAMES.get(dimensionList.get(1));
                        }
                        for (int j = 0; j < arrayShape[1]; j++) {
                            String name = "" + j;
                            if (innerDimensionNames != null) {
                                name = innerDimensionNames[j];
                            }
                            MetadataAttribute metadataAttribute = createMetadataAttribute(
                                    name, productData.getElemStringAt(count++)
                            );
                            innerElement.addAttribute(metadataAttribute);
                        }
                        element.addElement(innerElement);
                    } else {
                        String name = "" + i;
                        if (dimensionNames != null) {
                            name = dimensionNames[i];
                        }
                        MetadataAttribute metadataAttribute = createMetadataAttribute(
                                name, productData.getElemStringAt(count++)
                        );
                        element.addAttribute(metadataAttribute);
                    }
                }
                parentElement.addElement(element);
            }
        }
    }

    private void initGeoCoding(
            ZarrArray array, Map<String, Object> arrayAttributes, String arrayKey, List<String> coordinatePair
    ) throws FactoryException, TransformException, IOException, InvalidRangeException {
        int[] arrayShape = array.getShape();
        int[] shape = new int[]{arrayShape[arrayShape.length - 2], arrayShape[arrayShape.length - 1]};
        String shapeString = shape[0] + "_" + shape[1];
        if (!geoCodings.containsKey(shapeString)) {
            String crs_wkt = cast(getAttributeFromArrayAttributes(arrayAttributes, WKT_ATTRIBUTES_NAME));
            ArrayList<Number> transform = cast(getAttributeFromArrayAttributes(
                    arrayAttributes, TRANSFORM_ATTRIBUTES_NAME)
            );
            if (crs_wkt != null && transform != null) {
                CoordinateReferenceSystem crs = CRS.parseWKT(crs_wkt);
                double pixelSizeX = Math.abs(transform.get(0).doubleValue());
                double easting = transform.get(2).doubleValue();
                double pixelSizeY = Math.abs(transform.get(4).doubleValue());
                double northing = transform.get(5).doubleValue();
                CrsGeoCoding crsGeoCoding = new CrsGeoCoding(
                        crs, shape[0], shape[1], easting, northing, pixelSizeX, pixelSizeY
                );
                geoCodings.put(shapeString, crsGeoCoding);
            } else if (coordinatePair.contains("x")) {
                Map<String, Object> stac_map = cast(rootGroup.getAttributes().get(STAC_DISCOVERY_ATTRIBUTES_NAME));
                Map<String, Object> propertiesMap = cast(stac_map.get(PROPERTIES_ATTRIBUTES_NAME));
                int epsg_code = cast(propertiesMap.get("proj:epsg"));
                CoordinateReferenceSystem crs = CRS.decode("EPSG:" + epsg_code);
                String newKey = arrayKey.substring(0,arrayKey.lastIndexOf("/"));
                ProductData xCoordData = getProductDataFromKey(newKey, "x");
                int firstX = xCoordData.getElemIntAt(0);
                int secondX = xCoordData.getElemIntAt(1);
                int pixelSizeX = secondX - firstX;
                int easting = firstX - (pixelSizeX / 2);
                ProductData yCoordData = getProductDataFromKey(newKey, "y");
                int firstY = yCoordData.getElemIntAt(0);
                int secondY = yCoordData.getElemIntAt(1);
                int pixelSizeY = firstY - secondY;
                int northing = firstY + (pixelSizeY / 2);
                CrsGeoCoding crsGeoCoding = new CrsGeoCoding(
                        crs, shape[0], shape[1], easting, northing, pixelSizeX, pixelSizeY
                );
                geoCodings.put(shapeString, crsGeoCoding);
            } else {
                String newKey = arrayKey.substring(0,arrayKey.lastIndexOf("/"));
                Band[] coordinateBands = new Band[2];
                double[][] coordinateValues = new double[2][];
                int numCoordinates;
                for (int k = 0; k < coordinatePair.size(); k++) {
                    int count = 0;
                    String coordinateName = coordinatePair.get(k);
                    String coordKey = newKey + "/" + coordinateName;
                    ZarrArray coord = rootGroup.openArray(coordKey);
                    final DataType zarrDataType = coord.getDataType();
                    int productDataType = getProductDataType(zarrDataType);
                    numCoordinates = coord.getShape()[0];
                    coordinateValues[k] = new double[numCoordinates * numCoordinates];
                    ProductData productData = ProductData.createInstance(productDataType, numCoordinates);
                    coord.read(productData.getElems(), new int[]{numCoordinates});
                    String bandName = getBandName(coordKey.split("/"));
                    Band coordBand = new Band(bandName, productDataType, numCoordinates, numCoordinates);
                    coordBand.ensureRasterData();
                    for (int i = 0; i < numCoordinates; i++) {
                        for (int j = 0; j < numCoordinates; j++) {
                            if (k == 0) {
                                coordBand.setPixelDouble(i, j, productData.getElemDoubleAt(j));
                                coordinateValues[k][count++] = productData.getElemDoubleAt(j);
                            } else {
                                coordBand.setPixelDouble(i, j, productData.getElemDoubleAt(i));
                                coordinateValues[k][count++] = productData.getElemDoubleAt(i);
                            }
                        }
                    }
                    coordinateBands[k] = coordBand;
                    product.addBand(coordBand);
                }
                double resolutionInKm = RasterUtils.computeResolutionInKm(
                        coordinateValues[0], coordinateValues[1], shape[0], shape[1]
                );
                CoordinateReferenceSystem crs = DefaultGeographicCRS.WGS84;
                GeoRaster geoRaster = new GeoRaster(
                        coordinateValues[0], coordinateValues[1],
                        coordinateBands[0].getName(), coordinateBands[1].getName(),
                        shape[0], shape[1], resolutionInKm
                );
                final ForwardCoding forwardCoding = ComponentFactory.getForward(PixelForward.KEY);
                final InverseCoding inverseCoding = ComponentFactory.getInverse(PixelQuadTreeInverse.KEY);
                ComponentGeoCoding geoCoding = new ComponentGeoCoding(geoRaster, forwardCoding, inverseCoding, crs);
                geoCoding.setGeoCRS(DefaultGeographicCRS.WGS84);
                geoCoding.initialize();
                geoCodings.put(shapeString, geoCoding);
            }
        }
    }

    private ProductData getProductDataFromKey(
            String newKey, String coordName
    ) throws IOException, InvalidRangeException {
        String coordKey = newKey + "/" + coordName;
        ZarrArray coord = rootGroup.openArray(coordKey);
        int[] shape = coord.getShape();
        if (shape.length == 0) {
            ArrayParams params = new ArrayParams();
            params.byteOrder(coord.getByteOrder());
            params.dataType(coord.getDataType());
            params.fillValue(coord.getFillValue());
            params.shape(1);
            params.chunks(1);
            coord = rootGroup.createArray(coordKey, params);
        }
        final DataType zarrDataType = coord.getDataType();
        int productDataType = getProductDataType(zarrDataType);
        int numCoordinates = 1;
        for (int s : shape) {
            numCoordinates *= s;
        }
        ProductData productData = ProductData.createInstance(productDataType, numCoordinates);
        coord.read(productData.getElems(), shape);
        return productData;
    }


    private void addFlagCoding(Band band, Map<String, Object> attributes) {
        final String rasterName = band.getName();
        final List<String> flagMeanings = cast(attributes.get(FLAG_MEANINGS));
        if (flagMeanings != null) {
            String flagCodingName = null;
            for (Map.Entry<String, String> bandNameToFlagEntry : BAND_NAME_TO_FLAG_CODING_NAME.entrySet()) {
                if (rasterName.contains(bandNameToFlagEntry.getKey())) {
                    flagCodingName = bandNameToFlagEntry.getValue();
                    break;
                }
            }
            if (flagCodingName == null) {
                LOG.warning("Unexpected flag meanings found for band '" + rasterName + "'.");
                return;
            }
            FlagCoding flagCoding;
            if (band.getProduct().getFlagCodingGroup().contains(flagCodingName)) {
                flagCoding = band.getProduct().getFlagCodingGroup().get(flagCodingName);
            } else {
                flagCoding = createFlagCoding(band, attributes, flagCodingName);
            }
            band.setSampleCoding(flagCoding);
        }
    }

    private static FlagCoding createFlagCoding(Band band, Map<String, Object> attributes, String flagCodingName) {
        String rasterName = band.getName();
        final List<String> flagMeanings = cast(attributes.get(FLAG_MEANINGS));
        final List<Number> flagMasks = cast(attributes.get(FLAG_MASKS));

        FlagCoding flagCoding;
        final Product product = band.getProduct();
        if (flagMasks != null) {
            flagCoding = new FlagCoding(flagCodingName);
            product.getFlagCodingGroup().add(flagCoding);
        } else {
            LOG.warning("Raster attributes for '" + rasterName
                    + "' contains the attribute '" + FLAG_MEANINGS
                    + "' but no attribute '" + FLAG_MASKS + "'."
            );
            return null;
        }
        for (int i = 0; i < flagMeanings.size(); i++) {
            final String meaningName = flagMeanings.get(i);
            final String description = getFlagDescription(attributes, i);
            final int flagMask = flagMasks.get(i).intValue();
            flagCoding.addFlag(meaningName, flagMask, description);
        }
        return flagCoding;
    }

    private static String getFlagDescription(Map<String, Object> attributes, int pos) {
        if (attributes.containsKey(FLAG_DESCRIPTIONS)) {
            final List<String> list = cast(attributes.get(FLAG_DESCRIPTIONS));
            return list.get(pos);
        }
        return null;
    }

    private void addIndexCoding(Band band) {
        final String rasterName = band.getName();
        String indexCodingName = null;
        for (Map.Entry<String, String> bandNameToIndexEntry : BAND_NAME_TO_INDEX_CODING_NAME.entrySet()) {
            if (rasterName.contains(bandNameToIndexEntry.getKey())) {
                indexCodingName = bandNameToIndexEntry.getValue();
                break;
            }
        }
        if (indexCodingName == null) {
            return;
        }
        IndexCoding indexCoding;
        if (band.getProduct().getIndexCodingGroup().contains(indexCodingName)) {
            indexCoding = band.getProduct().getIndexCodingGroup().get(indexCodingName);
        } else if (indexCodingName.equals(DETECTOR_INDEX_CODING_NAME)) {
            indexCoding = createDetectorIndexCoding();
        } else {
            LOG.warning("Could not create index coding for band '" + rasterName + "'.");
            return;
        }
        band.setSampleCoding(indexCoding);
    }

    private IndexCoding createDetectorIndexCoding() {
        IndexCoding indexCoding = new IndexCoding(DETECTOR_INDEX_CODING_NAME);
        product.getIndexCodingGroup().add(indexCoding);
        int num_detectors = 12;
        indexCoding.addIndex("no_detector", 0, "no detector");
        for (int i = 1; i <= num_detectors; i++) {
            final String meaningName = "detector_" + i;
            final String description = "detector " + i;
            indexCoding.addIndex(meaningName, i, description);
        }
        return indexCoding;
    }

    private void addMasks(Band band) {
        String rasterName = band.getName();
        SampleCoding sampleCoding = band.getSampleCoding();
        if (sampleCoding == null) {
            return;
        }
        for (int i = 0; i < sampleCoding.getNumAttributes(); i++ ){
            final String sampleName = sampleCoding.getSampleName(i);
            final int sampleValue = sampleCoding.getSampleValue(i);
            String maskExpression;
            if (band.isFlagBand()) {
                maskExpression = rasterName + "." + sampleName;
            } else {
                maskExpression = rasterName + " == " + sampleValue;
            }
            final String maskName = rasterName + "_" + sampleName;
            final Color maskColor = getColorProvider().getMaskColor();
            product.addMask(maskName, maskExpression, maskExpression, maskColor, 0.5);
        }
    }

    private ColorProvider getColorProvider() {
        if (colorProvider == null) {
            colorProvider = new ColorProvider();
        }

        return colorProvider;
    }

    private Map<String, Object> getBandDescription(Map<String, Object> productAttributes, String bandName) {
        Map<String, Object> otherMetadataAttributes = cast(productAttributes.get(OTHER_METADATA_ATTRIBUTES_NAME));
        if (otherMetadataAttributes.containsKey(BAND_DESCRIPTION_ATTRIBUTES_NAME)) {
            Map<String, Object> bandDescription = cast(otherMetadataAttributes.get(BAND_DESCRIPTION_ATTRIBUTES_NAME));
            if (bandDescription.containsKey(bandName)) {
                return cast(bandDescription.get(bandName));
            }
        }
        return null;
    }

    private Band createBand(String arrayKey, ZarrArray array, int[] additionalIndices) {
        final DataType zarrDataType = array.getDataType();
        int productDataType = getProductDataType(zarrDataType);
        int[] shape = array.getShape();
        int[] shape2d = new int[]{shape[shape.length - 2], shape[shape.length - 1]};
        int[] chunks = array.getChunks();
        int[] chunks2d = new int[]{chunks[chunks.length - 2], chunks[chunks.length - 1]};
        int width = shape[shape.length - 2];
        int height = shape[shape.length - 1];
        final Band band = new Band(arrayKey, productDataType, width, height);
        product.addBand(band);
        String shapeString = shape2d[0] + "_" + shape2d[1];
        if (geoCodings.containsKey(shapeString)) {
            GeoCoding geoCoding = geoCodings.get(shapeString);
            band.setGeoCoding(geoCoding);
            RenderedImage sourceImage = new S2ZarrOpImage(
                    band, shape2d, chunks2d, additionalIndices, array, ResolutionLevel.MAXRES
            );
            if (geoCoding instanceof CrsGeoCoding) {
                AffineTransform2D i2m = (AffineTransform2D) geoCoding.getImageToMapTransform();
                double scaleX = i2m.getScaleX();
                double scaleY = i2m.getScaleY();
                double transformX = i2m.getTranslateX();
                double transformY = i2m.getTranslateY();
                double translateX = (transformX + Math.abs(0.5 * scaleX)) / scaleX;
                double translateY = (transformY - Math.abs(0.5 * scaleY)) / scaleY;
                final AffineTransform imageToModelTransform = new AffineTransform();
                imageToModelTransform.scale(scaleX, scaleY);
                if (!Double.isNaN(translateX) && !Double.isNaN(translateY)) {
                    imageToModelTransform.translate(translateX, translateY);
                }
                final DefaultMultiLevelModel targetModel = new DefaultMultiLevelModel(
                        imageToModelTransform, sourceImage.getWidth(), sourceImage.getHeight()
                );
                final DefaultMultiLevelSource targetMultiLevelSource =
                        new DefaultMultiLevelSource(sourceImage, targetModel);
                sourceImage = new DefaultMultiLevelImage(targetMultiLevelSource);
            } else {
                GeoCoding referenceGeoCoding = product.getSceneGeoCoding();
                S2ZarrGeoCodingSceneTransformProvider sceneTransformProvider =
                        new S2ZarrGeoCodingSceneTransformProvider(referenceGeoCoding, geoCoding);
                band.setModelToSceneTransform(sceneTransformProvider.getModelToSceneTransform());
                band.setSceneToModelTransform(sceneTransformProvider.getSceneToModelTransform());
            }
            band.setSourceImage(sourceImage);
        }
        return band;
    }

    void applyBandAttributes(Band band, Map<?, ?> bandDescription, Map<String, Object> arrayAttributes) {
        if (bandDescription != null) {
            if (bandDescription.containsKey(BANDWITH_ATTRIBUTES_NAME)) {
                Number bandwidth = (Number) bandDescription.get(BANDWITH_ATTRIBUTES_NAME);
                band.setSpectralBandwidth(bandwidth.floatValue());
            }
            if (bandDescription.containsKey(CENTRAL_WAVELENGTH_ATTRIBUTES_NAME)) {
                Number wavelength = (Number) bandDescription.get(CENTRAL_WAVELENGTH_ATTRIBUTES_NAME);
                band.setSpectralWavelength(wavelength.floatValue());
            }
        }
        if (arrayAttributes.containsKey(LONG_NAME_ATTRIBUTES_NAME)) {
            String description = arrayAttributes.get(LONG_NAME_ATTRIBUTES_NAME).toString();
            band.setDescription(description);
        }
        Object scaleFactor = getAttributeFromArrayAttributes(arrayAttributes, SCALE_FACTOR_ATTRIBUTES_NAME);
        if (scaleFactor != null) {
            band.setScalingFactor(((Number) scaleFactor).doubleValue());
        }
        Object addOffset = getAttributeFromArrayAttributes(arrayAttributes, ADD_OFFSET_ATTRIBUTES_NAME);
        if (addOffset != null) {
            band.setScalingOffset(((Number) addOffset).doubleValue());
        }
        Object fillValue = getAttributeFromArrayAttributes(arrayAttributes, FILL_VALUE_ATTRIBUTES_NAME);
        if (fillValue != null) {
            band.setNoDataValue(((Number) fillValue).doubleValue());
            band.setNoDataValueUsed(true);
        }
    }

    private Object getAttributeFromArrayAttributes(Map<String, Object> arrayAttributes, String attributeName) {
        Object attribute = getAttributeFromAttributes(arrayAttributes, attributeName);
        if (attribute == null) {
            if (arrayAttributes.containsKey(EOPF_ATTRS_ATTRIBUTES_NAME)) {
                Map<String, Object> eopfAttributes = cast(arrayAttributes.get(EOPF_ATTRS_ATTRIBUTES_NAME));
                attribute = getAttributeFromAttributes(eopfAttributes, attributeName);

            }
        }
        return attribute;
    }

    private Object getAttributeFromAttributes(Map<?, ?> attributeMap, String attributeName) {
        if (attributeMap.containsKey(attributeName)) {
            return attributeMap.get(attributeName);
        }
        return null;
    }

}
