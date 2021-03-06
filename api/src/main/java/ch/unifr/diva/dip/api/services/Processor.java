package ch.unifr.diva.dip.api.services;

import ch.unifr.diva.dip.api.components.Preset;
import ch.unifr.diva.dip.api.components.InputPort;
import ch.unifr.diva.dip.api.components.OutputPort;
import ch.unifr.diva.dip.api.components.Port;
import ch.unifr.diva.dip.api.components.ProcessorContext;
import ch.unifr.diva.dip.api.components.ProcessorDocumentation;
import ch.unifr.diva.dip.api.datatypes.DataType;
import ch.unifr.diva.dip.api.parameters.Parameter;
import ch.unifr.diva.dip.api.ui.NamedGlyph;
import ch.unifr.diva.dip.api.utils.L10n;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.beans.property.BooleanProperty;

/**
 * Processor interface. The processor interface is the core interface of a DIP
 * processor service, but doesn't do much on it's own. There are two main ways
 * to let a processor actually do something, either by implementing the
 * {@code Processable}, and/or the {@code Editable} interface. Both extend the
 * {@code Resetable} interface, which in turn extends this very interface. All
 * in all this gives a set of 4 main processor types:
 *
 * <ol>
 * <li>A processable processor for automatic processing,</li>
 * <li>an editable processor for manual processing/interaction,</li>
 * <li>a hybrid processor that is both; processable and editable, and</li>
 * <li>a plain processor that doesn't process anything at all (special case used
 * to serve the page/original image in a root processor/generator).</li>
 * </ol>
 *
 * <p>
 * There are three distinct uses of a {@code Processor} service:
 *
 * <ol>
 * <li>A non-initialized instance ({@code init()} is not called) is published to
 * the service registry and acts as a service factory (by implementing the
 * {@code newInstance()} method).</li>
 *
 * <li>Non-runnable (or passive/latent) instances (used in the pipeline editor)
 * are spawned with a call to {@code newInstance()}, and initialized
 * {@code init()} with no (or a {@code null}) processor context.</li>
 *
 * <li>Runnable processor instances are spawned with a call to
 * {@code newInstance()} and initialized {@code init()} with a valid processor
 * context.</li>
 * </ol>
 *
 * <p>
 * Non-runnable and runnable processors may change their form and shape (e.g. a
 * different set of input- and/or output-ports; e.g. after changing a parameter,
 * or after (dis-)connecting some port). The {@code repaint()} method has to be
 * called manually after any such change. Note that all parameters/ports should
 * be active (or published) after the constructor has been called, such that the
 * processor (as a service) can be fully inspected. Just a subset may then be
 * activated in the {@code init()} method, and changed as parameters or
 * connections are modified.
 *
 * <p>
 * Further interfaces can be implemented to offer more functionality:
 * <dl>
 * <dt>Previewable</dt>
 * <dd>For processors that offer preview functionality.</dd>
 * </dl>
 *
 */
public interface Processor {

	/**
	 * The state of a processor.
	 */
	public enum State {

		/**
		 * The processor is in an invalid state and needs to be reset.
		 */
		ERROR(L10n.getInstance().getString("state.error"), 32),
		/**
		 * The processor is currently unavailable.
		 */
		UNAVAILABLE(L10n.getInstance().getString("state.unavailable"), 16),
		/**
		 * The processor has required but unconnected input ports.
		 */
		UNCONNECTED(L10n.getInstance().getString("state.unconnected"), 8),
		/**
		 * The processor has input ports connected to output ports (of a
		 * preceeding processor) that aren't ready yet (or input parameters
		 * which aren't set/satisfied).
		 */
		WAITING(L10n.getInstance().getString("state.waiting"), 4),
		/**
		 * There are input ports (of a subsequent processor) connected to output
		 * ports on this processor, but the output ports (or output parameters)
		 * aren't set/ready yet. Or, no output ports are connected at all and
		 * not ready either. In the latter case the processor can be considered
		 * as IDLE/IDLING (since no outputs need to be served, if we're lazy),
		 * yet there is no real distinction between PROCESSING and IDLE, hence
		 * there is no separate state needed for that.
		 */
		PROCESSING(L10n.getInstance().getString("state.processing"), 2),
		/**
		 * All connected output ports have been set/served. Or all output ports
		 * are set/served, even if no output ports are connected at all (i.e.
		 * while IDLE/IDLING, see PROCESSING state).
		 */
		READY(L10n.getInstance().getString("state.ready"), 1);

		/**
		 * Localized label of the state.
		 */
		public final String label;

		/**
		 * Weight of the state. Used to imply a state of a whole processing
		 * stage, s.t. the processor state with the highest weight determines
		 * the implied state of the stage.
		 */
		public final int weight;

		private State(String label, int weight) {
			this.label = label;
			this.weight = weight;
		}

		/**
		 * Returns the state corresponding to a given weight.
		 *
		 * @param weight the weight of the state.
		 * @return the state with the given weight, or UNAVAILABLE if no such
		 * state exists.
		 */
		public static State getState(int weight) {
			for (State state : State.values()) {
				if (state.weight == weight) {
					return state;
				}
			}
			return State.UNAVAILABLE;
		}
	}

	/**
	 * Returns a new processor instance for the given context. Note that at this
	 * point the processor hasn't been fully initialized by its wrapper yet
	 * (e.g. parameters aren't set/restored yet). Use the {@code init()} hook
	 * method to finish processor initialization (if needed).
	 *
	 * @see #init(ch.unifr.diva.dip.api.components.ProcessorContext)
	 * @param context the processor context used to save and restore the
	 * internal processor state.
	 * @return a new instance of a processor.
	 */
	public Processor newInstance(ProcessorContext context);

	/**
	 * Hook method called as soon as a new instance of a processor has been
	 * fully initialized by its wrapper. In particular parameters all have been
	 * set at this point, and ports are connected as well.
	 *
	 * <ul>
	 * <li>Processors are supposed to restore their configuration (parameters),
	 * and furthermore their state if they are part of a runnable pipeline (as
	 * opposed to just being part of a pipeline in the pipeline editor, in which
	 * case {@code context} will be {@code null}).</li>
	 *
	 * <li>This is also the preferred point to attach any kind of
	 * Invalidation-/Changelisteners listening to ports or parameters or what
	 * not.</li>
	 * </ul>
	 *
	 * @param context the processor context used to save and restore the
	 * internal processor state, or {@code null}. Note that the context is
	 * {@code null} for processors used in the pipeline editor by a
	 * {@code Pipeline} (as opposed to be used in an {@code RunnablePipeline}),
	 * so an implementation of this hook needs to handle this case gracefully!
	 */
	void init(ProcessorContext context);

	/**
	 * Returns the name of the processor.
	 *
	 * @return the name of the processor.
	 */
	public String name();

	/**
	 * Returns (optional) processor documentation. If overwritten/implemented
	 * this method should always return a new instance of
	 * {@code ProcessorDocumentation}.
	 *
	 * @return the processor documentation, or {@code null} if not available.
	 */
	default ProcessorDocumentation processorDocumentation() {
		return null;
	}

	/**
	 * Returns the glyph of the processor.
	 *
	 * @return the glyph of the processor, or {@code null} (for no special
	 * glyph).
	 */
	default NamedGlyph glyph() {
		return null;
	}

	/**
	 * Returns the default/pre-installed presets of the processor. These
	 * processor presets are installed the first time this version
	 * ({@code major.minor} part only) of the processor is seen on the user's
	 * system. The user might still whish to delete/rearrange them at some
	 * point, hence this is done only once.
	 *
	 * <p>
	 * In order to reinstall these presets, the user would have to remove the
	 * corresponding entry from the {@code processor-recollection.xml} file in
	 * the DIP user directory.
	 *
	 * @return the default/pre-installed presets of the processor.
	 */
	default List<Preset> presets() {
		return Collections.emptyList();
	}

	/**
	 * Returns the parameters of the processor.
	 *
	 * @return a map of the parameters of the processor indexed by their keys.
	 */
	default Map<String, Parameter<?>> parameters() {
		return Collections.emptyMap();
	}

	/**
	 * Checks whether the processor has parameters or not.
	 *
	 * @return {@code true} if the processor has parameters, {@code false}
	 * otherwise.
	 */
	default boolean hasParameters() {
		return !parameters().isEmpty();
	}

	/**
	 * Returns an input port addressed by its key.
	 *
	 * @param key key of the input port.
	 * @return an input port, or {@code null} if not defined.
	 */
	default InputPort<?> input(String key) {
		return inputs().get(key);
	}

	/**
	 * Returns a map of all inputs. Keys are unique w.r.t. the input-set of a
	 * single processor.
	 *
	 * @return a map of all inputs indexed by their keys.
	 */
	public Map<String, InputPort<?>> inputs();

	/**
	 * Returns a list of all input port keys with the given data type.
	 *
	 * @param dataType the data type.
	 * @return list of input port keys.
	 */
	default List<String> inputs(DataType<?> dataType) {
		return inputs(dataType.getClass().getName());
	}

	/**
	 * Returns a list of all input port keys with the given data type.
	 *
	 * @param dataType name of the class of the data type.
	 * @return list of input port keys.
	 */
	default List<String> inputs(String dataType) {
		final List<String> ports = new ArrayList<>();
		for (Map.Entry<String, InputPort<?>> item : inputs().entrySet()) {
			final String t = item.getValue().getDataType().getClass().getName();
			if (dataType.equals(t)) {
				ports.add(item.getKey());
			}
		}
		return ports;
	}

	/**
	 * Returns a list of all port datatypes.
	 *
	 * @param ports list of ports.
	 * @return list of port datatypes.
	 */
	default List<String> portTypes(Map<String, ? extends Port<?>> ports) {
		final List<String> types = new ArrayList<>();
		for (Port<?> input : ports.values()) {
			types.add(input.getDataType().getClass().getName());
		}
		return types;
	}

	/**
	 * Returns all dependent inputs. Dependent inputs are input ports of
	 * different processors connected to an output port of this processor. Given
	 * multiple inputs may be connected to the same output port, this method
	 * returns a set of dependent inputs for each connected output port of this
	 * processor.
	 *
	 * @return a map of sets of dependent inputs, indexed by the key of the
	 * output port they're connected to.
	 */
	default Map<String, Set<InputPort<?>>> dependentInputs() {
		final Map<String, Set<InputPort<?>>> ports = new HashMap<>();
		for (Map.Entry<String, OutputPort<?>> e : outputs().entrySet()) {
			final String key = e.getKey();
			final OutputPort<?> output = e.getValue();
			ports.put(key, output.connections());
		}
		return ports;
	}

	/**
	 * Returns an output port addressed by its key.
	 *
	 * @param key key of the output port.
	 * @return an output port, or {@code null} if not defined.
	 */
	default OutputPort<?> output(String key) {
		return outputs().get(key);
	}

	/**
	 * Returns a map of all outputs. Keys are unique w.r.t. the output-set of a
	 * single processor.
	 *
	 * @return a map of all outputs indexed by their keys.
	 */
	public Map<String, OutputPort<?>> outputs();

	/**
	 * Returns a list of all output port keys with the given data type.
	 *
	 * @param dataType the data type.
	 * @return list of output port keys.
	 */
	default List<String> outputs(DataType<?> dataType) {
		return outputs(dataType.getClass().getName());
	}

	/**
	 * Returns a list of all output port keys with the given data type.
	 *
	 * @param dataType name of the class of the data type.
	 * @return list of output port keys.
	 */
	default List<String> outputs(String dataType) {
		final List<String> ports = new ArrayList<>();
		for (Map.Entry<String, OutputPort<?>> item : outputs().entrySet()) {
			final String t = item.getValue().getDataType().getClass().getName();
			if (dataType.equals(t)) {
				ports.add(item.getKey());
			}
		}
		return ports;
	}

	/**
	 * Disconnects all inputs and outputs of the processor.
	 */
	default void disconnect() {
		final List<InputPort<?>> removeIns = new ArrayList<>(inputs().values());
		for (InputPort<?> input : removeIns) {
			if (input.isConnected()) {
				input.disconnect();
			}
		}

		final List<OutputPort<?>> removeOuts = new ArrayList<>(outputs().values());
		for (OutputPort<?> output : removeOuts) {
			if (output.isConnected()) {
				output.disconnect();
			}
		}
	}

	/**
	 * Checks whether the processor is in an invalid state.
	 *
	 * @return {@code true} if the processor is in an invalid state,
	 * {@code false} otherwise.
	 */
	default boolean isError() {
		return false;
	}

	/**
	 * Checks if all required inputs are connected to some outputs.
	 *
	 * @return {@code true} if all required inputs are connected, {@code false}
	 * otherwise.
	 */
	default boolean isConnected() {
		for (InputPort<?> input : inputs().values()) {
			if (input.isRequired() && !input.isConnected()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks if all connected (not necessarily required) inputs can be consumed
	 * (or if the processor was ready all along). The processor might be ready
	 * even if some inputs aren't available; for example if preceeding
	 * processors (and their inputs) got reset while this processor remains
	 * ready.
	 *
	 * @return {@code true} if all outputs the inputs are connected to are
	 * READY, or if the processor is ready even with inputs not ready yet,
	 * {@code false} otherwise.
	 */
	default boolean isWaiting() {

		// unless we already are ready (e.g. preceeding processors might have been
		// reset, thus clearing the inputs, while this processor remains to be
		// ready, unless it got reseted too...)
		if (isReady()) {
			return false;
		}

		if (isWaitingOnInputParams()) {
			return true;
		}

		for (InputPort<?> input : inputs().values()) {
			if (input.isConnected()
					&& !input.connection().getPortState().equals(Port.State.READY)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks if all input parameters are available/can be consumed. By default
	 * no such parameters are defined, so unless overwritten this method always
	 * returns {@code false}.
	 *
	 * @return {@code true} if some input parameters are not available,
	 * {@code false} otherwise.
	 */
	default boolean isWaitingOnInputParams() {
		return false;
	}

	/**
	 * Checks whether the processor is done with its job. If no outputs are
	 * connected, then all outputs must be set/ready to consider the processor
	 * as ready. Otherwise all connected outputs must be set/ready to consider
	 * the processer as ready.
	 *
	 * @return {@code true} if all connected outputs (or all outputs if none are
	 * connected) are READY, {@code false} otherwise.
	 */
	default boolean isReady() {
		if (!isReadyOutputParams()) {
			return false;
		}

		int numConnected = 0;
		int numReady = 0;

		for (OutputPort<?> output : outputs().values()) {
			if (output.isConnected()) {
				// if outputs are connected, they must be set/ready to consider
				// this processor as ready
				if (!output.getPortState().equals(Port.State.READY)) {
					return false;
				}
				numReady++;
				numConnected++;
			} else {
				if (output.getPortState().equals(Port.State.READY)) {
					numReady++;
				}
			}
		}

		// if no outs are connected, all ports must be set/ready to consider this
		// "IDLE" processor as ready.
		if (numConnected == 0 && numReady < outputs().size()) {
			return false;
		}

		return true;
	}

	/**
	 * Checks whether all output parameters are set/satisfied. By default no
	 * such parameters are defined, so unless overwritten this method always
	 * returns {@code true}.
	 *
	 * @return {@code true} if all output parameters are set/satisfied,
	 * {@code false} otherwise.
	 */
	default boolean isReadyOutputParams() {
		return true;
	}

	/**
	 * Checks the state of the processor.
	 *
	 * @return the state of the processor.
	 */
	default State state() {
		if (isError()) {
			return State.ERROR;
		}

		if (!isConnected()) {
			return State.UNCONNECTED;
		}

		if (isWaiting()) {
			return State.WAITING;
		}

		if (isReady()) {
			return State.READY;
		}

		return State.PROCESSING;
	}

	/**
	 * The repaint "property" (or rather toggle). Changing the value on the
	 * repaint property (e.g. by a call to {@code repaint()}) triggers the
	 * processor wrapper (listening to this property) to reinstantiate/repaint
	 * the view, and reinitialize the processor (e.g. connections, since ports
	 * might have changed).
	 *
	 * @return the repaint "property" (or toggle), or {@code null} if not
	 * implemented.
	 */
	default BooleanProperty repaintProperty() {
		return null;
	}

	/**
	 * Repaints the processor (and its view). This method needs to be called
	 * upon changing ports, parameters, or the title/labels of the processor in
	 * order to reinstantiate/repaint the view, and reinitialize the processor
	 * (e.g. connections, since ports might have changed).
	 */
	default void repaint() {
		if (hasRepaintProperty()) {
			repaintProperty().set(!repaintProperty().get());
		}
	}

	/**
	 * Checks whether the processor offers a repaint property to listen to.
	 *
	 * @return {@code true} if there is a repaint property, {@code false}
	 * otherwise.
	 */
	default boolean hasRepaintProperty() {
		return repaintProperty() != null;
	}

	/**
	 * Checks whether the processor offers processing functionality. E.g. the
	 * PageGenerator that only serves an image does not process anything and is
	 * immediately READY.
	 *
	 * @return {@code true} if the processor (generally) has something to be
	 * processed, {@code false} otherwise.
	 */
	default boolean canProcess() {
		return (this instanceof Processable);
	}

	/**
	 * Checks whether the processor offers reset functionality.
	 *
	 * @return {@code true} if the processor (generally) can be reset,
	 * {@code false} otherwise.
	 */
	default boolean canReset() {
		return (this instanceof Resetable);
	}

	/**
	 * Checks whether the processor has manual editing functionality.
	 *
	 * @return {@code true} if the processor has manual editing functionality,
	 * {@code false} otherwise.
	 */
	default boolean canEdit() {
		return (this instanceof Editable);
	}

	/**
	 * Checks whether this is a hybrid processor with processing and manual
	 * editing functionality.
	 *
	 * @return {@code true} if this is a hybrid processor, {@code false}
	 * otherwise.
	 */
	default boolean isHybrid() {
		return canEdit() && canProcess();
	}

	/**
	 * Checks whether the processor has manual editing tools, or not.
	 *
	 * @return {@code true} if the processor has manual editing tools,
	 * {@code false} otherwise.
	 */
	default boolean hasTools() {
		if (!(this instanceof Editable)) {
			return false;
		}
		final Editable editable = (Editable) this;
		return !editable.tools().isEmpty();
	}

	/**
	 * Returns the processor as resetable processor. Make sure this processor
	 * {@code canReset()} first.
	 *
	 * @param <T> class of a resetable processor.
	 * @return a resetable processor.
	 */
	@SuppressWarnings("unchecked")
	default <T extends Processor & Resetable> T asResetableProcessor() {
		return (T) this;
	}

	/**
	 * Returns the processor as processable processor. Make sure this processor
	 * {@code canProcess()} first.
	 *
	 * @param <T> class of a processable processor.
	 * @return a processable processor.
	 */
	@SuppressWarnings("unchecked")
	default <T extends Processor & Processable> T asProcessableProcessor() {
		return (T) this;
	}

	/**
	 * Returns the processor as editable processor. Make sure this processor
	 * {@code canEdit()} first.
	 *
	 * @param <T> class of an editable processor.
	 * @return an editable processor.
	 */
	@SuppressWarnings("unchecked")
	default <T extends Processor & Editable> T asEditableProcessor() {
		return (T) this;
	}

	/**
	 * Returns the processor as hybrid processor. Make sure this processor
	 * {@code canProcess()} and {@code canEdit()} first.
	 *
	 * @param <T> class of a processable and editable processor.
	 * @return a processable and editable processor.
	 */
	@SuppressWarnings("unchecked")
	default <T extends Processor & Processable & Editable> T asHybridProcessor() {
		return (T) this;
	}

}
