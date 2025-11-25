package eu.esa.opt.dataio.s2;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class S2ZarrConstants {

    static final String AUTO_GROUPING =
            "atmosphere:cams:classification:detector_footprint:ecmwf:geometry:probability:quicklook:" +
            "mask:reflectance";
    static final String ZARR_FILE_EXTENSION = ".zarr";
    static final String ZIP_CONTAINER_EXTENSION = ".zarr.zip";
    static final String FORMAT_NAME = "Sentinel-2 ZARR Multi-Res";
    static final Class<?>[] IO_TYPES = new Class[]{
            Path.class,
            File.class,
            String.class
    };
    static Map<String, String[]> DIMENSION_NAMES = Map.of(
            "angle", new String[]{"zenith", "azimuth"},
            "band",
            new String[]{"b01", "b02", "b03", "b04", "b05", "b06", "b07", "b08", "b8a", "b09", "b10", "b11", "b12"}
    );

    static final List<String> RESOLUTIONS = Arrays.asList("r10m", "r20m", "r60m");

    static final String RGB_10M_IMAGE_PROFILE_NAME = "Sentinel-2 Zarr (10m)";
    static final String RGB_20M_IMAGE_PROFILE_NAME = "Sentinel-2 Zarr (20m)";
    static final String RGB_60M_IMAGE_PROFILE_NAME = "Sentinel-2 Zarr (60m)";

    static final String STAC_DISCOVERY_ATTRIBUTES_NAME = "stac_discovery";
    static final String PROPERTIES_ATTRIBUTES_NAME = "properties";
    static final String ARRAY_DIMENSIONS_ATTRIBUTES_NAME = "_ARRAY_DIMENSIONS";
    static final String BBOX_ATTRIBUTES_NAME = "proj:bbox";
    static final String EPSG_ATTRIBUTES_NAME = "proj:epsg";
    static final String HORIZONTAL_EPSG_ATTRIBUTES_NAME = "horizontal_CRS_code";
    static final String WKT_ATTRIBUTES_NAME = "proj:wkt2";
    static final String TRANSFORM_ATTRIBUTES_NAME = "proj:transform";
    static final String OTHER_METADATA_ATTRIBUTES_NAME = "other_metadata";
    static final String BAND_DESCRIPTION_ATTRIBUTES_NAME = "band_description";
    static final String BANDWITH_ATTRIBUTES_NAME = "bandwidth";
    static final String CENTRAL_WAVELENGTH_ATTRIBUTES_NAME = "central_wavelength";
    static final String LONG_NAME_ATTRIBUTES_NAME = "long_name";
    static final String SCALE_FACTOR_ATTRIBUTES_NAME = "scale_factor";
    static final String ADD_OFFSET_ATTRIBUTES_NAME = "add_offset";
    static final String FILL_VALUE_ATTRIBUTES_NAME = "fill_value";
    static final String EOPF_ATTRS_ATTRIBUTES_NAME = "_eopf_attrs";

    static final String[] SUN_ANGLES_LIST = new String[]{"sun_zenith", "sun_azimuth"};

    static final String[] VIEWING_ANGLES_BAND_LIST = new String[]{
            "b01", "b02", "b03", "b04", "b05", "b06", "b07", "b08", "b8a", "b09", "b10", "b11", "b12"
    };
    static final String[] VIEWING_ANGLES_DETECTOR_LIST = new String[]{
            "detector_2", "detector_3", "detector_4", "detector_5", "detector_6", "detector_7", "detector_8"
    };
    static final String[] VIEWING_ANGLES_ANGLE_LIST = new String[]{"view_zenith", "view_azimuth"};

    static final String QUICKLOOK_BAND_NAME = "quicklook";
    static final String[] QUICKLOOK_BAND_LIST = new String[]{"1", "2", "3"};

    static final Map<String, String[][]> BAND_NAME_PARTS = Map.of(
            "sun_angles", new String[][]{SUN_ANGLES_LIST},
            "viewing_incidence_angles", new String[][]{
                    VIEWING_ANGLES_BAND_LIST, VIEWING_ANGLES_DETECTOR_LIST, VIEWING_ANGLES_ANGLE_LIST
            },
            "tci", new String[][]{QUICKLOOK_BAND_LIST}
    );

    static final Map<String, String> BASE_NAMES = Map.of(
            "sun_angles", "geometry",
            "viewing_incidence_angles", "geometry"
    );

    // CF sample coding attributes
    public static final String FLAG_MASKS = "flag_masks";
    public static final String FLAG_MEANINGS = "flag_meanings";

    // Sample coding attributes
    public static final String FLAG_DESCRIPTIONS = "flag_descriptions";

    static final Map<String, String> BAND_NAME_TO_FLAG_CODING_NAME = Map.of(
            "l1c_classification", "l1c_classification",
            "l2a_classification", "l2a_classification",
            "mask", "quality"
    );

    static final String DETECTOR_INDEX_CODING_NAME = "detector_footprint";
    static final Map<String, String> BAND_NAME_TO_INDEX_CODING_NAME = Map.of(
            DETECTOR_INDEX_CODING_NAME, DETECTOR_INDEX_CODING_NAME
    );

}
