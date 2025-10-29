package eu.esa.opt.dataio.s2;

import java.awt.Color;

/**
 * @author Tonio Fincke
 */
public class ColorProvider {

    private int colourCounter;
    private int numberOfIntermediateSamplePoints;
    private int thirdPowerOfCurrentDivisions;
    private int numberOfTotalSamplePoints;

    public ColorProvider() {
        colourCounter = 0;
        numberOfIntermediateSamplePoints = 0;
        numberOfTotalSamplePoints = 2;
        evaluateMaxNumberOfSamplePointsForDivision();
    }

    private void evaluateMaxNumberOfSamplePointsForDivision() {
        thirdPowerOfCurrentDivisions = (int) Math.pow(numberOfTotalSamplePoints, 3);
    }

    public Color getMaskColor() {
        Color color = null;
        while (color == null) {
            int redStep = 0;
            int greenStep = 0;
            int blueStep = 0;
            double stepSize = 255.0;
            while (redStep % 2 == 0 && greenStep % 2 == 0 && blueStep % 2 == 0) {
                updateDivision();
                stepSize = 255.0 / (numberOfIntermediateSamplePoints + 1);
                redStep = (colourCounter / (numberOfTotalSamplePoints * numberOfTotalSamplePoints))
                        % numberOfTotalSamplePoints;
                greenStep = (colourCounter / numberOfTotalSamplePoints) % numberOfTotalSamplePoints;
                blueStep = colourCounter % numberOfTotalSamplePoints;
                colourCounter++;
            }
            color = new Color((int) (redStep * stepSize), (int) (greenStep * stepSize), (int) (blueStep * stepSize));
        }
        return color;
    }

    private void updateDivision() {
        if (colourCounter > thirdPowerOfCurrentDivisions) {
            numberOfIntermediateSamplePoints = (numberOfIntermediateSamplePoints * 2) + 1;
            numberOfTotalSamplePoints = numberOfIntermediateSamplePoints + 2;
            evaluateMaxNumberOfSamplePointsForDivision();
            colourCounter = 0;
        }
    }

}
