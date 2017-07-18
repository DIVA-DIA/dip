package ch.unifr.diva.dip.api.datastructures;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * A 2D polyline defined by a list of {@code Point2D}s.
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.NONE)
public class Polyline2D extends AbstractList<Point2D> {

	/**
	 * Creates a new, empty polyline.
	 */
	public Polyline2D() {
		this(new ArrayList<>());
	}

	/**
	 * Creates a new polyline.
	 *
	 * @param points points of the polyline.
	 */
	public Polyline2D(List<Point2D> points) {
		super(points);
	}

	/**
	 * Returns a copy of the polyline.
	 *
	 * @return a copy of the polyline.
	 */
	public Polyline2D copy() {
		final Polyline2D copy = new Polyline2D();
		for (Point2D point : elements) {
			copy.add(point); // no need to copy; points are final
		}
		return copy;
	}

}
