package eu.esa.opt.dataio.s2;

import com.bc.zarr.DataType;
import org.esa.snap.core.datamodel.ProductData;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;


public class S2ZarrUtils {

    static Path convertToPath(final Object object) {
        if (object instanceof Path) {
            return (Path) object;
        }
        if (object instanceof File) {
            return ((File) object).toPath();
        }
        if (object instanceof String) {
            return Paths.get((String) object);
        }
        return null;
    }

    static int getProductDataType(DataType zarrDataType) {
        if (zarrDataType == DataType.f8) {
            return ProductData.TYPE_FLOAT64;
        } else if (zarrDataType == DataType.f4) {
            return ProductData.TYPE_FLOAT32;
        } else if (zarrDataType == DataType.i1) {
            return ProductData.TYPE_INT8;
        } else if (zarrDataType == DataType.u1) {
            return ProductData.TYPE_UINT8;
        } else if (zarrDataType == DataType.i2) {
            return ProductData.TYPE_INT16;
        } else if (zarrDataType == DataType.u2) {
            return ProductData.TYPE_UINT16;
        } else if (zarrDataType == DataType.i4) {
            return ProductData.TYPE_INT32;
        } else if (zarrDataType == DataType.u4) {
            return ProductData.TYPE_UINT32;
        } else {
            throw new IllegalStateException();
        }
    }

    public static <T> T cast(Object o) {
        return (T) o;
    }

}
