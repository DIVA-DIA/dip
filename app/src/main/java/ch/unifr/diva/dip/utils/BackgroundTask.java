package ch.unifr.diva.dip.utils;

import ch.unifr.diva.dip.api.utils.FxUtils;
import ch.unifr.diva.dip.core.ApplicationHandler;
import ch.unifr.diva.dip.core.ui.UIStrategy;
import ch.unifr.diva.dip.eventbus.EventBus;
import ch.unifr.diva.dip.eventbus.events.StatusWorkerEvent;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper for JavaFX {@code Task}s with optional status communication over an
 * event-bus.
 *
 * @param <T> The result type returned by this FutureTask's get methods.
 */
public abstract class BackgroundTask<T> extends Task<T> {

	private static final Logger log = LoggerFactory.getLogger(BackgroundTask.class);
	private final UIStrategy uiStrategy;
	private final EventBus eventBus;
	private final Thread thread;
	private final ObjectProperty<Result> resultProperty;

	/**
	 * The result of a background task.
	 */
	public enum Result {

		/**
		 * The task is still running (or not even started yet).
		 */
		RUNNING,
		/**
		 * The task was successful.
		 */
		SUCCEEDED,
		/**
		 * The task got cancelled.
		 */
		CANCELLED,
		/**
		 * The task failed.
		 */
		FAILED
	}

	/**
	 * Creates a new background task.
	 */
	public BackgroundTask() {
		this(null, null);
	}

	/**
	 * Creates a new background task.
	 *
	 * @param eventBus the event bus (to handle status messages/progress
	 * notification).
	 */
	public BackgroundTask(EventBus eventBus) {
		this(null, eventBus);
	}

	/**
	 * Creates a new background task.
	 *
	 * @param handler the application handler (to get the UI strategy and the
	 * event bus).
	 */
	public BackgroundTask(ApplicationHandler handler) {
		this(handler.uiStrategy, handler.eventBus);
	}

	/**
	 * Creates a new background task.
	 *
	 * @param uiStrategy the UI strategy (to show potential errors).
	 * @param eventBus the event bus (to handle status messages/progress
	 * notification).
	 */
	public BackgroundTask(UIStrategy uiStrategy, EventBus eventBus) {
		this.uiStrategy = uiStrategy;
		this.eventBus = eventBus;
		this.thread = new Thread(this);
		this.resultProperty = new SimpleObjectProperty<>(Result.RUNNING);
	}

	/**
	 * Returns the result property. Starts out as {@code RUNNING}, and
	 * eventually turns to {@code SUCCEEDED}, {@code CANCELLED}, or
	 * {@code FAILED}.
	 *
	 * @return
	 */
	public ReadOnlyObjectProperty<Result> resultProperty() {
		return resultProperty;
	}

	/**
	 * Returns the thread executing this background task.
	 *
	 * @return the thread executing this background task.
	 */
	public Thread getThread() {
		return thread;
	}

	/**
	 * Starts the background task.
	 *
	 * @return the thread of the background task.
	 */
	public Thread start() {
		if (eventBus != null) {
			eventBus.post(new StatusWorkerEvent<>(this));
		}
		thread.start();
		return thread;
	}

	/**
	 * Executes a runnable on the JavaFx application thread.
	 *
	 * @param runnable a runnable.
	 */
	protected void runLater(Runnable runnable) {
		Platform.runLater(runnable);
	}

	/**
	 * Hook method to clean up that get's called in {@code cancelled()} and
	 * {@code failed()} methods. Get's called before error handling in
	 * {@code failed()}. Also overwrite {@code succeeded()} in order to handle
	 * all possible outcomes (or just {@code finished()}).
	 *
	 * <p>
	 * Just like the {@code succeeded()}, {@code cancelled()}, and
	 * {@code failed()} methods, this method is invoked on the FX Application
	 * thread.
	 */
	protected void cleanUp() {

	}

	/**
	 * Hook method that get's called either way; once done, cancelled, or
	 * failed. Get's called before {@code cleanUp()} and before error handling
	 * in {@code failed()}.
	 *
	 * <p>
	 * Just like the {@code succeeded()}, {@code cancelled()}, and
	 * {@code failed()} methods, this method is invoked on the FX Application
	 * thread.
	 *
	 * @param state state of the BackgroundTask.
	 */
	protected void finished(Result state) {

	}

	@Override
	protected void succeeded() {
		finished(Result.SUCCEEDED);

		FxUtils.run(() -> {
			resultProperty.set(Result.SUCCEEDED);
		});
	}

	@Override
	protected void cancelled() {
		finished(Result.CANCELLED);
		cleanUp();

		FxUtils.run(() -> {
			resultProperty.set(Result.CANCELLED);
		});
	}

	@Override
	protected void failed() {
		finished(Result.FAILED);
		cleanUp();

		FxUtils.run(() -> {
			resultProperty.set(Result.FAILED);
		});

		final Throwable throwable = this.exceptionProperty().get();
		if (throwable instanceof Exception) {
			// Exception (recoverable)
			final Exception ex = (Exception) throwable;
			log.error("background task {} threw an exception: ", this, ex);
		} else {
			// Error (unrecoverable)
			final Error error = (Error) throwable;
			log.error("background task {} produced an (unrecoverable) error: ", this, error);
		}

		if (uiStrategy != null) {
			uiStrategy.showError(throwable);
		}
	}

}
