package ch.unifr.diva.dip.awt.tools;

import ch.unifr.diva.dip.api.components.OutputPort;
import ch.unifr.diva.dip.api.components.ProcessorContext;
import ch.unifr.diva.dip.api.datastructures.DoubleMatrix;
import ch.unifr.diva.dip.api.datastructures.FloatMatrix;
import ch.unifr.diva.dip.api.datastructures.StringMatrix;
import ch.unifr.diva.dip.api.parameters.ButtonParameter;
import ch.unifr.diva.dip.api.parameters.CompositeGrid;
import ch.unifr.diva.dip.api.parameters.EmptyParameter;
import ch.unifr.diva.dip.api.parameters.ExpMatrixParameter;
import ch.unifr.diva.dip.api.parameters.ExpParameter;
import ch.unifr.diva.dip.api.parameters.IntegerParameter;
import ch.unifr.diva.dip.api.parameters.TextParameter;
import ch.unifr.diva.dip.api.services.Processor;
import ch.unifr.diva.dip.api.services.ProcessorBase;
import javafx.beans.InvalidationListener;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;

/**
 * Matrix editor/generator. Manually configure and provide (small) matrices,
 * e.g. to be used as kernels for convolution filters.
 *
 * <p>
 * This plugin is not suited for larger matrices.
 */
@Component
@Service
public class MatrixEditor extends ProcessorBase {

	public static final int MAX_ROWS = 64;
	public static final int MAX_COLUMNS = MAX_ROWS;

	private final OutputPort<FloatMatrix> output_float;
	private final OutputPort<DoubleMatrix> output_double;

	private final IntegerParameter columns;
	private final IntegerParameter rows;
	private final ExpMatrixParameter matrix;
	private final ExpParameter multiplier;

	private final InvalidationListener dimensionListener;

	public MatrixEditor() {
		super("Matrix Editor");

		final double dimWidth = 35;
		final double singleWidth = dimWidth * 2 + 15;
		final int defaultSize = 3;

		this.columns = new IntegerParameter("columns", defaultSize, 1, MAX_COLUMNS);
		this.columns.addTextFieldViewHook((tf) -> tf.setPrefWidth(dimWidth));

		this.rows = new IntegerParameter("rows", defaultSize, 1, MAX_ROWS);
		this.rows.addTextFieldViewHook((tf) -> tf.setPrefWidth(dimWidth));

		this.matrix = new ExpMatrixParameter("matrix", new StringMatrix(defaultSize, defaultSize));
		this.matrix.setCellWidth(singleWidth + this.matrix.getCellSpacing() * 2);

		this.multiplier = new ExpParameter("multiplier", "1");
		this.multiplier.addTextFieldViewHook((tf) -> tf.setPrefWidth(singleWidth));

		final CompositeGrid dimensions = new CompositeGrid(
				"Dimensions",
				this.rows,
				new TextParameter(" x "),
				this.columns
		);
		this.parameters.put("dimensions", dimensions);

		final ExpParameter matModifier = new ExpParameter("matmod", "1");
		matModifier.addTextFieldViewHook((tf) -> tf.setPrefWidth(singleWidth));
		final ButtonParameter modFill = new ButtonParameter("fill", (c) -> {
			final String v = matModifier.get();
			this.matrix.set(this.matrix.get().fill(v));
		});
		final CompositeGrid console = new CompositeGrid(
				matModifier,
				modFill
		);
		console.setHorizontalSpacing(3, 8);

		final CompositeGrid mat = new CompositeGrid(
				"Matrix",
				this.multiplier,
				new TextParameter(" x "),
				this.matrix,
				new EmptyParameter(),
				new EmptyParameter(),
				console
		);
		mat.setColumnConstraints(3);
		mat.setVerticalSpacing(10);
		this.parameters.put("matrix", mat);

		this.dimensionListener = (obs) -> {
			final int m = this.rows.get();
			final int n = this.columns.get();
			final StringMatrix a = this.matrix.get();
			if (a.rows != m || a.columns != n) {
				this.matrix.set(new StringMatrix(m, n));
			}
		};

		this.output_float = new OutputPort(new ch.unifr.diva.dip.api.datatypes.FloatMatrix());
		this.output_double = new OutputPort(new ch.unifr.diva.dip.api.datatypes.DoubleMatrix());

		this.outputs.put("float-matrix", output_float);
		this.outputs.put("double-matrix", output_double);
	}

	@Override
	public Processor newInstance(ProcessorContext context) {
		return new MatrixEditor();
	}

	@Override
	public void init(ProcessorContext context) {
		this.rows.property().addListener(dimensionListener);
		this.columns.property().addListener(dimensionListener);

		// only set/update outputs if this is for a runnable processor!
		if (context != null) {
			setOutputs();
			this.matrix.property().addListener(matrixListener);
		}
	}

	private final InvalidationListener matrixListener = (c) -> {
		setOutputs();
	};

	private void setOutputs() {
		// use multiplier only if not equal to 1
		final double mul = this.multiplier.getDouble();
		final boolean doMul = new Double(1).compareTo(mul) != 0;

		if (this.output_float.isConnected()) {
			final FloatMatrix fmat = doMul
					? this.matrix.get().getFloatMatrix().multiply((float) mul)
					: this.matrix.get().getFloatMatrix();
			this.output_float.setOutput(fmat);
		}
		if (this.output_double.isConnected()) {
			final DoubleMatrix dmat = doMul
					? this.matrix.get().getDoubleMatrix().multiply(mul)
					: this.matrix.get().getDoubleMatrix();
			this.output_double.setOutput(dmat);
		}
	}

}