package ch.unifr.diva.dip.api.datastructures;

import javafx.scene.paint.Color;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Wrapper class to marshall JavaFX {@code Color} objects.
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.NONE)
public class FxColor {

	/**
	 * The intensity of the red channel. Intensity values range from 0 to 255.
	 */
	@XmlAttribute
	public final int red;

	/**
	 * The intensity of the green channel. Intensity values range from 0 to 255.
	 */
	@XmlAttribute
	public final int green;

	/**
	 * The intensity of the blue channel. Intensity values range from 0 to 255.
	 */
	@XmlAttribute
	public final int blue;

	/**
	 * The intensity of the opacity channel. Intensity values range from 0 to
	 * 255.
	 */
	@XmlAttribute
	public final int opacity;

	/**
	 * Creates a new FxColor object. The initial color is set to black.
	 */
	public FxColor() {
		this(Color.BLACK);
	}

	/**
	 * Creates a new FxColor object.
	 *
	 * @param color the initial color.
	 */
	public FxColor(Color color) {
		this.red = toInteger(color.getRed());
		this.green = toInteger(color.getGreen());
		this.blue = toInteger(color.getBlue());
		this.opacity = toInteger(color.getOpacity());
	}

	/**
	 * Creates a new FxColor object. Intensity values range from 0 to 255.
	 *
	 * @param red the intensity of the red channel.
	 * @param green the intensity of the green channel.
	 * @param blue the intensity of the blue channel.
	 */
	public FxColor(int red, int green, int blue) {
		this(red, green, blue, 255);
	}

	/**
	 * Creates a new FxColor object. Intensity values range from 0 to 255.
	 *
	 * @param red the intensity of the red channel.
	 * @param green the intensity of the green channel.
	 * @param blue the intensity of the blue channel.
	 * @param opacity the intensity of the opacity channel.
	 */
	public FxColor(int red, int green, int blue, int opacity) {
		this.red = red;
		this.green = green;
		this.blue = blue;
		this.opacity = opacity;
	}

	@Override
	public String toString() {
		return this.getClass().getSimpleName()
				+ "@"
				+ Integer.toHexString(hashCode())
				+ "{"
				+ "red: " + red
				+ ", green: " + green
				+ ", blue: " + blue
				+ ", opacity: " + opacity
				+ "}";
	}

	@Override
	public int hashCode() {
		int hash = 17;
		hash = 31 * hash + Integer.hashCode(red);
		hash = 31 * hash + Integer.hashCode(green);
		hash = 31 * hash + Integer.hashCode(blue);
		hash = 31 * hash + Integer.hashCode(opacity);
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final FxColor other = (FxColor) obj;
		if (this.red != other.red) {
			return false;
		}
		if (this.green != other.green) {
			return false;
		}
		if (this.blue != other.blue) {
			return false;
		}
		if (this.opacity != other.opacity) {
			return false;
		}
		return true;
	}

	/**
	 * Returns a JavaFX {@code Color} object.
	 *
	 * @return a JavaFX {@code Color} object.
	 */
	public Color toColor() {
		return Color.rgb(red, green, blue, toDouble(opacity));
	}

	/**
	 * Compares a JavaFX {@code Color} object to the color encoded in this
	 * {@code FxColor} object.
	 *
	 * @param color the JavaFX {@code Color} object.
	 * @return {@code true} if the colors are equal, {@code false} otherwise.
	 */
	public boolean equalsColor(Color color) {
		if (this.red != (int) color.getRed()) {
			return false;
		}
		if (this.green != (int) color.getGreen()) {
			return false;
		}
		if (this.blue != (int) color.getBlue()) {
			return false;
		}
		if (this.opacity != (int) color.getOpacity()) {
			return false;
		}
		return true;
	}

	protected static int toInteger(double value) {
		return (int) (value * 255);
	}

	protected static double toDouble(int value) {
		return (double) value / 255.0;
	}

}
