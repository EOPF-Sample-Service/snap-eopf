package eu.esa.opt.dataio.s2;

import org.esa.snap.core.util.io.SnapFileFilter;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class S2ZarrProductReaderPluginTest {

    private S2ZarrProductReaderPlugin plugin;
    private final String ZARR_TEST_PRODUCT = "S2A_MSIL2A_20180701T102021_N0500_R065_T32UPC_20230811T042458.zarr";

    @Before
    public void setUp() {
        plugin = new S2ZarrProductReaderPlugin();
    }

    @Test
    public void testGetDefaultFileExtensions() {
        Assert.assertArrayEquals(new String[]{".zarr", ".zarr.zip"}, plugin.getDefaultFileExtensions());
    }

    @Test
    public void testGetDescription() {
        assertEquals("Sentinel-2 ZARR Multi-Res product reader", plugin.getDescription(null));
    }

    @Test
    public void testGetFormatNames() {
        Assert.assertArrayEquals(new String[]{"Sentinel-2 ZARR Multi-Res"}, plugin.getFormatNames());
    }

    @Test
    public void testGetInputTypes(){
        Assert.assertArrayEquals(
                new Class[]{
                        Path.class,
                        File.class,
                        String.class
                },
                plugin.getInputTypes()
        );
    }

    @Test
    public void testGetProductFileFilter_Accept() throws URISyntaxException {
        SnapFileFilter filter = plugin.getProductFileFilter();
        Assert.assertNotNull(filter);
        final URL resource = getClass().getResource(ZARR_TEST_PRODUCT);
        Assert.assertNotNull(resource);
        final URI uri = new URI(resource.toString());
        File zarrFile = new File(uri.getPath());
        Assert.assertTrue(filter.accept(zarrFile));
        Assert.assertFalse(filter.accept(zarrFile.getParentFile()));
    }

    @Test
    public void testGetProductFileFilter_isCompoundDocument() throws URISyntaxException {
        SnapFileFilter filter = plugin.getProductFileFilter();
        Assert.assertNotNull(filter);
        final URL resource = getClass().getResource(ZARR_TEST_PRODUCT);
        Assert.assertNotNull(resource);
        final URI uri = new URI(resource.toString());
        File zarrFile = new File(uri.getPath());
        Assert.assertTrue(filter.isCompoundDocument(zarrFile));
        Assert.assertFalse(filter.isCompoundDocument(zarrFile.getParentFile()));
    }

}
