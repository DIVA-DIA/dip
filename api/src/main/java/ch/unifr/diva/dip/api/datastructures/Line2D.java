package ch.unifr.diva.dip.api.datastructures;

import java.util.Objects;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * A 2D line defined by two {@code Point2D}s.
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.NONE)
public class Line2D {

	@XmlElement
	public final Point2D end;

	@XmlElement
	public final Point2D start;

	@SuppressWarnings("unused")
	public Line2D() {
		this(new Point2D(), new Point2D());
	}

	/**
	 * Creates a new 2D line.
	 *
	 * @param startX the X coordinate of the start point.
	 * @param startY the Y coordinate of the start point.
	 * @param endX the X coordinate of the end point.
	 * @param endY the Y coordinate of the end point.
	 */
	public Line2D(double startX, double startY, double endX, double endY) {
		this(new Point2D(startX, startY), new Point2D(endX, endY));
	}

	/**
	 * Creates a new 2D line.
	 *
	 * @param start the start point.
	 * @param end the end point.
	 */
	public Line2D(Point2D start, Point2D end) {
		this.end = end;
		this.start = start;
	}

	@Override
	public String toString() {
		return this.getClass().getSimpleName()
				+ "@"
				+ Integer.toHexString(hashCode())
				+ "{"
				+ "start=" + start
				+ ", end=" + end
				+ "}";
	}

	@Override
	public int hashCode() {
		int hash = 17;
		hash = 31 * hash + end.hashCode();
		hash = 31 * hash + start.hashCode();
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
		final Line2D other = (Line2D) obj;
		if (!Objects.equals(end, other.end)) {
			return false;
		}
		return Objects.equals(start, other.start);
	}

}
