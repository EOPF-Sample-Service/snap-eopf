package eu.esa.opt.dataio.s2;

import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.ProductReaderPlugIn;
import org.esa.snap.core.util.io.SnapFileFilter;

import static com.bc.zarr.ZarrConstants.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static eu.esa.opt.dataio.s2.S2ZarrConstants.*;
import static eu.esa.opt.dataio.s2.S2ZarrUtils.convertToPath;

public class S2ZarrProductReaderPlugin implements ProductReaderPlugIn  {

    @Override
    public DecodeQualification getDecodeQualification(Object input) {
        final Path inputPath = convertToPath(input);
        if (inputPath == null) {
            return DecodeQualification.UNABLE;
        }
        final Path productRoot;
        final String lowerName = inputPath.getFileName().toString().toLowerCase();
        if (lowerName.endsWith(ZARR_FILE_EXTENSION)) {
            productRoot = inputPath;
        } else {
            productRoot = inputPath.getParent();
        }
        Path productRootName = productRoot != null ? productRoot.getFileName() : null;
        final boolean isValidRootDirName =
                productRootName != null &&
                        productRootName.toString().toLowerCase().endsWith(ZARR_FILE_EXTENSION);
        if (isValidRootDirName) {
            final boolean productRootIsDirectory = Files.isDirectory(productRoot);
            final Path productHeader = productRoot.resolve(FILENAME_DOT_ZGROUP);
            final boolean productHeaderExist = Files.exists(productHeader);
            final boolean productHeaderIsFile = Files.isRegularFile(productHeader);

            if (productRootIsDirectory && productHeaderExist && productHeaderIsFile) {
                try (Stream<Path> stream = Files.find(productRoot, 5,
                        (path, basicFileAttributes) -> Files.isRegularFile(path) && path.endsWith(FILENAME_DOT_ZARRAY),
                        FileVisitOption.FOLLOW_LINKS)) {
                    final List<Path> pathList = stream.toList();
                    if (!pathList.isEmpty()) {
                        return DecodeQualification.INTENDED;
                    }
                } catch (IOException e) {
                    return DecodeQualification.UNABLE;
                }
            }
        }
        return DecodeQualification.UNABLE;
    }

    @Override
    public Class[] getInputTypes() {
        return IO_TYPES;
    }

    @Override
    public ProductReader createReaderInstance() {
        return new S2ZarrProductReader(this);
    }

    @Override
    public String[] getFormatNames() {
        return new String[]{FORMAT_NAME};
    }

    @Override
    public String[] getDefaultFileExtensions() {
        return new String[]{ZARR_FILE_EXTENSION};
    }

    @Override
    public String getDescription(Locale locale) {
        return FORMAT_NAME + " product reader";
    }

    @Override
    public SnapFileFilter getProductFileFilter() {
        return new SnapFileFilter(getFormatNames()[0], getDefaultFileExtensions(), getDescription(null)) {

            @Override
            public boolean accept(File file) {
                return isZarrRootDir(file) || isFileInZarrRootDir(file);
            }

            @Override
            public boolean isCompoundDocument(File dir) {
            return isZarrRootDir(dir);
        }

            private boolean isFileInZarrRootDir(File file) {
                return file != null && isZarrRootDir(file.getParentFile());
            }

            private boolean isZarrRootDir(File file) {
                return file != null && file.isDirectory() && hasContainerExtension(file);
            }

            private boolean hasContainerExtension(File file) {
                return file.getName().endsWith(ZARR_FILE_EXTENSION);
            }
        };
    }
}
