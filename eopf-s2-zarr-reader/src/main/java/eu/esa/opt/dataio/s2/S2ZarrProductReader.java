package eu.esa.opt.dataio.s2;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.zarr.DataType;
import com.bc.zarr.ZarrArray;
import com.bc.zarr.ZarrGroup;
import com.bc.zarr.storage.FileSystemStore;
import org.esa.snap.core.dataio.AbstractProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.datamodel.quicklooks.Quicklook;
import org.esa.snap.core.image.ResolutionLevel;
import org.esa.snap.core.util.ISO8601Converter;
import org.geotools.referencing.CRS;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;

import java.awt.*;
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
    private final Map<int[], GeoCoding> geoCodings = new HashMap<>();
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
        
        initTiepointGridsAndBands(productAttributes);
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
//            }
        }
        return null;
    }

    private void initTiepointGridsAndBands(Map<String, Object> productAttributes) throws IOException {
        // only bands at the moment
        List<String> xy = new ArrayList<>(List.of("x", "y"));
        for (String arrayKey : rootGroup.getArrayKeys()) {
            try {
                ZarrArray array = rootGroup.openArray(arrayKey);
                String[] splitArrayKey = arrayKey.split("/");
                String parentFolder = splitArrayKey[splitArrayKey.length - 2];
                String origBandName = splitArrayKey[splitArrayKey.length - 1];
                Map<?, ?> bandDescription = getBandDescription(productAttributes, origBandName);
                String bandName;
                if (RESOLUTIONS.contains(parentFolder)) {
                    String grandParentFolder = splitArrayKey[splitArrayKey.length - 3];
                    bandName = origBandName + "_" + parentFolder + "_" + grandParentFolder;
                } else {
                    bandName = origBandName + "_" + parentFolder;
                }

                Map<String, Object> arrayAttributes = array.getAttributes();
                if (arrayAttributes.containsKey(ARRAY_DIMENSIONS_ATTRIBUTES_NAME)) {
                    List<String> dimensionList = cast(arrayAttributes.get(ARRAY_DIMENSIONS_ATTRIBUTES_NAME));
                    if (new HashSet<>(dimensionList).containsAll(xy)) {
                        int[] shape = array.getShape();
                        Band band;
                        if (shape.length == 2) {
                            band = createBand(bandName, array, new int[0]);
                            applyBandAttributes(band, bandDescription, arrayAttributes);
                            setGeoCoding(band, array, arrayAttributes);
                            addFlagCoding(band, arrayAttributes);
                            addIndexCoding(band);
                            addMasks(band);
                        } else {
                            String[][] bandNameParts = BAND_NAME_PARTS.get(origBandName);
                            int numDims = shape.length - 2;
                            int [] additionalIndexes = new int[numDims];
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
                                setGeoCoding(band, array, arrayAttributes);
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
                        }
                    }
                }
            } catch (IllegalArgumentException iae) {
                // TODO: handle this correctly
                System.out.println(arrayKey + ": " + iae);
            }
        }
    }

    private void setGeoCoding(Band band, ZarrArray array, Map<String, Object> arrayAttributes) {
        int[] arrayShape = array.getShape();
        int[] shape = new int[]{arrayShape[arrayShape.length - 2], arrayShape[arrayShape.length - 1]};
        if (!geoCodings.containsKey(shape)) {
            String crs_wkt = cast(getAttributeFromArrayAttributes(arrayAttributes, WKT_ATTRIBUTES_NAME));
            ArrayList<Number> transform = cast(getAttributeFromArrayAttributes(
                    arrayAttributes, TRANSFORM_ATTRIBUTES_NAME)
            );
            if (crs_wkt != null && transform != null) {
                try {
                    CoordinateReferenceSystem crs = CRS.parseWKT(crs_wkt);
                    double pixelSizeX = Math.abs(transform.get(0).doubleValue());
                    double easting = transform.get(2).doubleValue();
                    double pixelSizeY = Math.abs(transform.get(4).doubleValue());
                    double northing = transform.get(5).doubleValue();
                    CrsGeoCoding crsGeoCoding = new CrsGeoCoding(
                            crs, shape[0], shape[1], easting, northing, pixelSizeX, pixelSizeY
                    );
                    geoCodings.put(shape, crsGeoCoding);
                } catch (FactoryException | TransformException e) {
                    // TODO handling
                }
            }
        }
        if (geoCodings.containsKey(shape)) {
            band.setGeoCoding(geoCodings.get(shape));
        }
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
        final S2ZarrOpImage zarrOpImage = new S2ZarrOpImage(
                band, shape2d, chunks2d, additionalIndices, array, ResolutionLevel.MAXRES
        );
        band.setSourceImage(zarrOpImage);
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
