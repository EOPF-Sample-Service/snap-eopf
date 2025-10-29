package eu.esa.opt.dataio.s2;

import com.bc.zarr.DataType;
import com.bc.zarr.ZarrArray;
import com.bc.zarr.ZarrGroup;
import com.bc.zarr.storage.FileSystemStore;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.image.ResolutionLevel;
import org.junit.Test;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

import static eu.esa.opt.dataio.s2.S2ZarrUtils.getProductDataType;
import static org.junit.Assert.*;


public class S2ZarrOpimageTest {

    private final String ONLY_SPATIAL_PRODUCT = "cams.zarr";
    private final String MULTI_DIMS_PRODUCT = "geometry.zarr";

    private ZarrGroup createZarrGroup(String product) throws URISyntaxException, IOException {
        final URL resource = getClass().getResource(product);
        assertNotNull(resource);
        final URI uri = new URI(resource.toString());
        File file = new File(uri.getPath());
        final Path inputPath = file.toPath();
        FileSystemStore store = new FileSystemStore(inputPath);
        return ZarrGroup.open(store);
    }

    private S2ZarrOpImage createS2ZarrOpImage(String arrayKey, ZarrArray array, int[] additionalIndexes) {
        final DataType zarrDataType = array.getDataType();
        int productDataType = getProductDataType(zarrDataType);
        int[] shape = array.getShape();
        int[] chunks = array.getChunks();
        for (int i = 0; i < shape.length - 3; i++) {
            shape[i] = 1;
            chunks[i] = 1;
        }
        int width = shape[shape.length - 2];
        int height = shape[shape.length - 1];
        final Band band = new Band(arrayKey, productDataType, width, height);
        return new S2ZarrOpImage(
                band, shape, chunks, additionalIndexes, array, ResolutionLevel.MAXRES
        );
    }

    @Test
    public void testS2Zarr2DImage_openOnlySpatial() throws IOException, URISyntaxException {
        ZarrGroup group = createZarrGroup(ONLY_SPATIAL_PRODUCT);

        assertNotNull(group);

        ZarrArray array = group.openArray("aod1240");

        final DataType zarrDataType = array.getDataType();
        int productDataType = getProductDataType(zarrDataType);

        S2ZarrOpImage image = createS2ZarrOpImage("a0d1240", array, new int[0]);

        assertNotNull(image);

        ProductData productData = ProductData.createInstance(productDataType, 9 * 9);
        Rectangle rect = new Rectangle(9, 9);

        image.computeProductData(productData, rect);

        assertNotNull(productData);
        for (int i = 0; i < productData.getNumElems(); i++) {
            assertTrue(productData.getElemDoubleAt(i) > 0.0);
        }
    }

    @Test
    public void testS2Zarr2DImage_open3Dimensional() throws IOException, URISyntaxException {
        ZarrGroup group = createZarrGroup(MULTI_DIMS_PRODUCT);

        assertNotNull(group);

        ZarrArray array = group.openArray("sun_angles");

        final DataType zarrDataType = array.getDataType();
        int productDataType = getProductDataType(zarrDataType);

        for (int i = 0; i < 2; i++) {

            S2ZarrOpImage image = createS2ZarrOpImage("sun_angles", array, new int[]{i});

            assertNotNull(image);

            ProductData productData = ProductData.createInstance(productDataType, 23 * 23);
            Rectangle rect = new Rectangle(23, 23);

            image.computeProductData(productData, rect);

            assertNotNull(productData);
            for (int j = 0; j < productData.getNumElems(); j++) {
                assertTrue(productData.getElemDoubleAt(j) > 0.0);
            }
        }
    }

    @Test
    public void testS2Zarr2DImage_openMultiDimensional() throws IOException, URISyntaxException {
        ZarrGroup group = createZarrGroup(MULTI_DIMS_PRODUCT);

        assertNotNull(group);

        ZarrArray array = group.openArray("viewing_incidence_angles");

        final DataType zarrDataType = array.getDataType();
        int productDataType = getProductDataType(zarrDataType);

        for (int i = 0; i < 13; i++) {
            for (int j = 0; j < 7; j++) {
                for (int k = 0; k < 2; k++) {
                    S2ZarrOpImage image = createS2ZarrOpImage(
                            "viewing_incidence_angles", array, new int[]{i, j, k}
                    );
                    assertNotNull(image);
                    ProductData productData = ProductData.createInstance(productDataType, 23 * 23);
                    Rectangle rect = new Rectangle(23, 23);

                    image.computeProductData(productData, rect);

                    assertNotNull(productData);
                    for (int l = 0; l < productData.getNumElems(); l++) {
                        double elem = productData.getElemDoubleAt(l);
                        if (!Double.isNaN(elem)) {
                            assertTrue(productData.getElemDoubleAt(l) > 0.0);
                        }
                    }
                }
            }
        }
    }

}
