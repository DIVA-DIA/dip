package ch.unifr.diva.dip.api.imaging.ops;

import ch.unifr.diva.dip.api.imaging.BufferedMatrix;
import ch.unifr.diva.dip.api.imaging.scanners.Location;
import ch.unifr.diva.dip.api.imaging.scanners.RasterScanner;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;

/**
 * {@code NullOp} is a general base class implementation of
 * {@code BufferedImageOp} that can be easily subclassed to provide specific
 * image processing functions. While {@code NullOp} is a complete implementation
 * of {@code BufferedImageOp} (that simply copies each source sample to the
 * destination), it is meant to be subclassed by overriding the {@code filter}
 * method.
 */
public class NullOp implements BufferedImageOp {

	@Override
	public BufferedImage createCompatibleDestImage(BufferedImage src, ColorModel dstCM) {
		// buffered matrix?
		if (src instanceof BufferedMatrix) {
			final BufferedMatrix mat = (BufferedMatrix) src;
			return new BufferedMatrix(
					mat.getWidth(),
					mat.getHeight(),
					mat.getNumBands(),
					mat.getSampleDataType(),
					mat.getInterleave()
			);
		}

		// common type?
		if (src.getType() != 0) {
			return new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
		}

		// custom type
		return new BufferedImage(
				dstCM,
				dstCM.createCompatibleWritableRaster(src.getWidth(), src.getHeight()),
				dstCM.isAlphaPremultiplied(),
				null
		);
	}

	/**
	 * Creates a zeroed destination image with the correct size and number of
	 * bands.
	 *
	 * @param bounds bounds/size of the destination image.
	 * @param dstCM ColorModel of the destination.
	 * @return the zeroed destination image.
	 */
	public BufferedImage createCompatibleDestImage(Rectangle bounds, ColorModel dstCM) {
		return new BufferedImage(
				dstCM,
				dstCM.createCompatibleWritableRaster(bounds.width, bounds.height),
				dstCM.isAlphaPremultiplied(),
				null
		);
	}

	/**
	 * Creates a zeroed destination matrix with the correct size, precision and
	 * number of bands.
	 *
	 * @param bounds bounds/size of the destination matrix.
	 * @param src source matrix the destination matrix should be compatible to
	 * (defines numBands, sampleDataType, and interleave).
	 * @return the zeroed destination matrix.
	 */
	public BufferedMatrix createCompatibleDestMatrix(Rectangle bounds, BufferedMatrix src) {
		return new BufferedMatrix(
				bounds.width,
				bounds.height,
				src.getNumBands(),
				src.getSampleDataType(),
				src.getInterleave()
		);
	}

	@Override
	public BufferedImage filter(BufferedImage src, BufferedImage dst) {
		if (dst == null) {
			dst = createCompatibleDestImage(src, src.getColorModel());
		}

		final WritableRaster srcRaster = src.getRaster();
		final WritableRaster dstRaster = dst.getRaster();

		for (Location pt : new RasterScanner(src, true)) {
			final int sample = srcRaster.getSample(pt.col, pt.row, pt.band);
			dstRaster.setSample(pt.col, pt.row, pt.band, sample);
		}

		return dst;
	}

	@Override
	public Rectangle2D getBounds2D(BufferedImage src) {
		return src.getRaster().getBounds();
	}

	@Override
	public Point2D getPoint2D(Point2D src, Point2D dst) {
		if (dst == null) {
			dst = (Point2D) src.clone();
		} else {
			dst.setLocation(src);
		}

		return dst;
	}

	@Override
	public RenderingHints getRenderingHints() {
		return null;
	}

}
