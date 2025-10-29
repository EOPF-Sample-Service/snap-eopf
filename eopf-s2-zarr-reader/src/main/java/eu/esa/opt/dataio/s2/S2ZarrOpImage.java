package eu.esa.opt.dataio.s2;

import com.bc.ceres.multilevel.MultiLevelImage;
import com.bc.ceres.multilevel.MultiLevelModel;
import com.bc.zarr.ZarrArray;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.esa.snap.core.image.ImageManager;
import org.esa.snap.core.image.ResolutionLevel;
import org.esa.snap.core.image.SingleBandedOpImage;
import org.esa.snap.core.util.ImageUtils;
import ucar.ma2.InvalidRangeException;

import javax.media.jai.PlanarImage;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.WritableRaster;
import java.io.IOException;

/**
 * A class to derive an {@code OpImage} from a {@code ZarrArray}.
 * The ZarrArray may be multidimensional,
 * in which case dimension indices must be specified for the non-spatial dimensions.
 *
 * @author Tonio Fincke
 * @see RasterDataNode#getSourceImage()
 * @see RasterDataNode#getGeophysicalImage()
 * @see RasterDataNode#setSourceImage(MultiLevelImage)
 */
public class S2ZarrOpImage extends SingleBandedOpImage {

    private final RasterDataNode rasterDataNode;
    private final ZarrArray arrayDataReader;
    private final int[] additionalIndices;

    /**
     * Constructor.
     *
     * @param rasterDataNode The target raster data node.
     * @param level          The resolution level.
     *
     * @see ResolutionLevel#create(MultiLevelModel, int)
     */
    public S2ZarrOpImage(RasterDataNode rasterDataNode, int[] shape, int[] chunks, int[] additionalIndices, ZarrArray reader, ResolutionLevel level) {
        super(ImageManager.getDataBufferType(rasterDataNode.getDataType()),
                shape[1], shape[0],
                new Dimension(chunks[1], chunks[0]),
                null, level);
        this.rasterDataNode = rasterDataNode;
        this.arrayDataReader = reader;
        this.additionalIndices = additionalIndices;
    }

    @Override
    public String toString() {
        String className = getClass().getSimpleName();
        String productName = "";
        if (rasterDataNode.getProduct() != null) {
            productName = ":" + rasterDataNode.getProduct().getName();
        }
        String bandName = "." + rasterDataNode.getName();
        return className + productName + bandName;
    }

    @Override
    protected void computeRect(PlanarImage[] sourceImages, WritableRaster tile, Rectangle destRect) {
        ProductData productData;
        boolean directMode = tile.getDataBuffer().getSize() == destRect.width * destRect.height;
        if (directMode) {
            productData = ProductData.createInstance(rasterDataNode.getDataType(),
                    ImageUtils.getPrimitiveArray(tile.getDataBuffer()));
        } else {
            productData = ProductData.createInstance(rasterDataNode.getDataType(),
                    destRect.width * destRect.height);
        }

        try {
            computeProductData(productData, destRect);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (!directMode) {
            tile.setDataElements(destRect.x, destRect.y, destRect.width, destRect.height, productData.getElems());
        }
    }

    /**
     * Computes the target pixel data for this level image.
     *
     * @param productData The target pixel buffer to write to. The number of elements in this buffer will always be
     *                    {@code region.width * region.height}.
     * @param region      The target region in pixel coordinates valid for this image level.
     *
     * @throws IOException May be thrown if an I/O error occurs during the computation.
     */
    protected void computeProductData(ProductData productData, Rectangle region) throws IOException {
        try {
            int fullDimSize = additionalIndices.length + 2;
            int[] bufferShape = new int[fullDimSize];
            int[] offset = new int[fullDimSize];
            for (int i = 0; i < fullDimSize - 2; i++) {
                bufferShape[i] = 1;
                offset[i] = additionalIndices[i];
            }
            bufferShape[fullDimSize - 2] = region.height;
            bufferShape[fullDimSize - 1] = region.width;
            offset[fullDimSize - 2] = region.y;
            offset[fullDimSize - 1] = region.x;
            arrayDataReader.read(productData.getElems(), bufferShape, offset);
        } catch (InvalidRangeException e) {
            throw new IOException(e);
        }
    }
}
