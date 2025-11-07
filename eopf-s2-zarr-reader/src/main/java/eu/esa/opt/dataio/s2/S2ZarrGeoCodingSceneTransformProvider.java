package eu.esa.opt.dataio.s2;

import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.transform.AbstractTransform2D;
import org.esa.snap.core.transform.MathTransform2D;
import org.opengis.referencing.operation.NoninvertibleTransformException;
import org.opengis.referencing.operation.TransformException;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

public class S2ZarrGeoCodingSceneTransformProvider implements SceneTransformProvider {

    private final GeoCoding sceneGeoCoding;
    private final GeoCoding modelGeoCoding;
    private final ModelToSceneTransform modelToSceneTransform;
    private final SceneToModelTransform sceneToModelTransform;

    public S2ZarrGeoCodingSceneTransformProvider(GeoCoding sceneGeoCoding, GeoCoding modelGeoCoding) {
        this.sceneGeoCoding = sceneGeoCoding;
        this.modelGeoCoding = modelGeoCoding;
        modelToSceneTransform = new ModelToSceneTransform();
        sceneToModelTransform = new SceneToModelTransform();
    }

    @Override
    public MathTransform2D getModelToSceneTransform() {
        return modelToSceneTransform;
    }

    @Override
    public MathTransform2D getSceneToModelTransform() {
        return sceneToModelTransform;
    }


    private abstract class SlstrSceneTransform extends AbstractTransform2D {

        protected Point2D transform(Point2D ptSrc, Point2D ptDst, GeoCoding from, GeoCoding to) throws TransformException {
            if (!from.canGetGeoPos() || !to.canGetPixelPos()) {
                throw new TransformException("Cannot transform");
            }
            AffineTransform modelToImage;
            try {
                modelToImage = Product.findImageToModelTransform(from).createInverse();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                modelToImage = new AffineTransform();
            }
            Point2D modelPtSrc = modelToImage.transform(ptSrc, new Point2D.Double());
            PixelPos pixelPos = new PixelPos(modelPtSrc.getX(), modelPtSrc.getY());
            final GeoPos geoPos = from.getGeoPos(pixelPos, new GeoPos());
            pixelPos = to.getPixelPos(geoPos, pixelPos);
            if (Double.isNaN(geoPos.getLat()) || Double.isNaN(geoPos.getLon()) ||
                    Double.isNaN(pixelPos.getX()) || Double.isNaN(pixelPos.getY())) {
                throw new TransformException("Cannot transform");
            }
            AffineTransform imageToModel = Product.findImageToModelTransform(to);
            Point2D scenePixelPos = imageToModel.transform(pixelPos, new Point2D.Double());
            ptDst.setLocation(scenePixelPos.getX(), scenePixelPos.getY());
            return ptDst;
        }

        GeoCoding getModelGeoCoding() {
            return modelGeoCoding;
        }

        GeoCoding getSceneGeoCoding() {
            return sceneGeoCoding;
        }

    }

    private class ModelToSceneTransform extends SlstrSceneTransform {

        @Override
        public Point2D transform(Point2D ptSrc, Point2D ptDst) throws TransformException {
            return transform(ptSrc, ptDst, modelGeoCoding, sceneGeoCoding);
        }

        @Override
        public MathTransform2D inverse() throws NoninvertibleTransformException {
            return sceneToModelTransform;
        }

        @Override
        public boolean equals(Object object) {
            if (object == this) {
                return true;
            }
            if (!(object instanceof ModelToSceneTransform)) {
                return false;
            }
            final ModelToSceneTransform that = (ModelToSceneTransform) object;
            return  that.getModelGeoCoding().equals(this.getModelGeoCoding()) &&
                    that.getSceneGeoCoding().equals(this.getSceneGeoCoding());
        }

        @Override
        public int hashCode() {
            return modelGeoCoding.hashCode() + sceneGeoCoding.hashCode() + 1;
        }
    }

    private class SceneToModelTransform extends SlstrSceneTransform {

        @Override
        public Point2D transform(Point2D ptSrc, Point2D ptDst) throws TransformException {
            return transform(ptSrc, ptDst, sceneGeoCoding, modelGeoCoding);
        }

        @Override
        public MathTransform2D inverse() throws NoninvertibleTransformException {
            return modelToSceneTransform;
        }

        @Override
        public boolean equals(Object object) {
            if (object == this) {
                return true;
            }
            if (!(object instanceof SceneToModelTransform)) {
                return false;
            }
            final SceneToModelTransform that = (SceneToModelTransform) object;
            return  that.getModelGeoCoding().equals(this.getModelGeoCoding()) &&
                    that.getSceneGeoCoding().equals(this.getSceneGeoCoding());
        }

        @Override
        public int hashCode() {
            return modelGeoCoding.hashCode() + sceneGeoCoding.hashCode() + 2;
        }
    }
}
