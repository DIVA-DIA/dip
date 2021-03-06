package ch.unifr.diva.dip.core;

import ch.unifr.diva.dip.api.utils.DipThreadPool;
import ch.unifr.diva.dip.core.model.PipelineData;
import ch.unifr.diva.dip.core.execution.PipelineExecutionLogger;
import ch.unifr.diva.dip.core.execution.TimingPipelineExecutionLogger;
import ch.unifr.diva.dip.core.ui.UIStrategy;
import ch.unifr.diva.dip.core.ui.Localizable;
import ch.unifr.diva.dip.core.model.ProjectData;
import ch.unifr.diva.dip.eventbus.EventBus;
import ch.unifr.diva.dip.eventbus.events.ProjectNotification;
import ch.unifr.diva.dip.eventbus.events.StatusMessageEvent;
import ch.unifr.diva.dip.core.model.Project;
import ch.unifr.diva.dip.eventbus.events.ProjectRequest;
import ch.unifr.diva.dip.osgi.OSGiFramework;
import ch.unifr.diva.dip.utils.BackgroundTask;
import ch.unifr.diva.dip.utils.ZipFileSystem;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javax.xml.bind.JAXBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ApplicationHandler (Chain of Responsibility).
 */
public class ApplicationHandler implements Localizable {

	private static final Logger log = LoggerFactory.getLogger(ApplicationHandler.class);
	private final ApplicationContext context;

	/**
	 * The application data manager.
	 */
	public final ApplicationDataManager dataManager;

	/**
	 * Application wide thread pool/executor service. This thread pool is also
	 * passed to processors to do their job, so keep this, and the fact that
	 * dependent tasks are a big NO, in mind, or face another talk with the
	 * dining philosophers.
	 *
	 * <p>
	 * For example the preview widget is not allowed to use this thread pool to
	 * update its viewport; all threads in the pool could end up being occupied
	 * by a preview task, which each in turn require a processor to return the
	 * preview image for which we need threads from this thread pool... and it's
	 * the Great Famine all over again (i.e. thread starvation). You get the
	 * idea...
	 */
	public final DipThreadPool threadPool;

	/**
	 * A discarding thread pool. This pool (with a single worker and a blocking
	 * array queue of size 1) is used to update viewports in ZoomPanes, hence
	 * its not so important if intermediate tasks get discarded.
	 *
	 * <p>
	 * Technically each such viewport (or similar) should have its own such
	 * pool, exactly because it's a discarding one (otherwise it's no longer
	 * guaranteed that the last task is always processed/never discarded), but
	 * we go by the assumption that only one client of this pool is actively in
	 * use at a time (it's rather hard to operate multiple ZoomPanes, or other
	 * GUI widgets at the same time).
	 *
	 * <p>
	 * If in doubt, just make your own thread pool (with blackjack, and...), no
	 * big deal. E.g. the preview widget uses it's own discarding thread pool,
	 * since it's exactly used together with a ZoomPane.
	 */
	public final DipThreadPool discardingThreadPool;

	/**
	 * OSGi framework.
	 */
	public final OSGiFramework osgi;

	/**
	 * The user settings.
	 */
	public final UserSettings settings;

	/**
	 * The UI strategy.
	 */
	public final UIStrategy uiStrategy;

	/**
	 * The application's event bus.
	 */
	public final EventBus eventBus;

	/**
	 * FX application host services. Used to open browsers. Only available
	 * running in GUI mode, will be {@code null} in headless mode.
	 */
	public final HostServices hostServices;

	// open/current project
	private Project project = null;
	// pointers to invalid/corrupt project data; might be fixed and still opened
	private ProjectData projectData = null;
	private ProjectData.ValidationResult projectValidation = null;

	private final ReadOnlyBooleanWrapper hasProjectProperty = new ReadOnlyBooleanWrapper();
	private final ReadOnlyBooleanWrapper modifiedProjectProperty = new ReadOnlyBooleanWrapper();
	private final ReadOnlyIntegerWrapper selectedPageProperty = new ReadOnlyIntegerWrapper();
	private final ReadOnlyBooleanWrapper canProcessPageProperty = new ReadOnlyBooleanWrapper();

	/**
	 * ApplicationHandler constructor.
	 *
	 * @param context the ApplicationContext.
	 * @param uiStrategy an UIStrategy (for error handling, confirmations, ...).
	 * @param eventBus the eventbus.
	 */
	public ApplicationHandler(
			ApplicationContext context,
			UIStrategy uiStrategy,
			EventBus eventBus
	) {
		this(context, uiStrategy, eventBus, null);
	}

	/**
	 * ApplicationHandler constructor.
	 *
	 * @param context the ApplicationContext.
	 * @param uiStrategy an UIStrategy (for error handling, confirmations, ...).
	 * @param eventBus the eventbus.
	 * @param hostServices FX application host services.
	 */
	public ApplicationHandler(
			ApplicationContext context,
			UIStrategy uiStrategy,
			EventBus eventBus,
			HostServices hostServices
	) {
		this.context = context;
		this.dataManager = context.dataManager;
		this.threadPool = context.threadPool;
		this.discardingThreadPool = context.discardingThreadPool;
		this.osgi = context.osgi;
		this.settings = context.settings;
		this.uiStrategy = uiStrategy;
		this.eventBus = eventBus;
		this.hostServices = hostServices;
	}

	/**
	 * Returns the most recently used DIP save directory. A DIP save directory
	 * is a directory where DIP project files have been stored (or accessed).
	 *
	 * @return the most recently used DIP save directory, or the user directory
	 * as a fallback.
	 */
	public Path getRecentSaveDirectory() {
		if (settings.recentFiles.getSaveDirectory() != null) {
			return settings.recentFiles.getSaveDirectory();
		}
		return ApplicationDataManager.userDirectory();
	}

	/**
	 * Returns the most recently accessed DIP data directory. A DIP data
	 * directory is a directory where DIP data files (holding presets and/or
	 * pipelines) have been stored (or accessed).
	 *
	 * @return the most recently accessed DIP data directory, or the user
	 * directory as a fallback.
	 */
	public Path getRecentDataDirectory() {
		if (settings.recentFiles.getDataDirectory() != null) {
			return settings.recentFiles.getDataDirectory();
		}
		return ApplicationDataManager.userDirectory();
	}

	/**
	 * Returns the most recently accessed DIP data file. A DIP data file holds
	 * presets and/or pipelines.
	 *
	 * @return the most recently accessed DIP data file, or {@code null}.
	 */
	public Path getRecentDataFile() {
		return getRecentDataFile(null);
	}

	/**
	 * Returns the most recently accessed DIP data file. A DIP data file holds
	 * presets and/or pipelines.
	 *
	 * @param fallback a fallback file (or {@code null}) to be returned in case
	 * there is no recently accessed DIP data file.
	 * @return the most recently accessed DIP data file, or the given fallback
	 * file.
	 */
	public Path getRecentDataFile(Path fallback) {
		if (settings.recentFiles.getDataFile() != null) {
			return settings.recentFiles.getDataFile();
		}
		return fallback;
	}

	public PipelineExecutionLogger getPipelineExecutorLogger() {
		// TODO: user setting? Not sure if really necessary...
		// either way this should always be something extending a timing logger
//		return new PrintingPipelineExecutionLogger();
		return new TimingPipelineExecutionLogger();
	}

	/**
	 * Creates a new project.
	 *
	 * @param name the name of the new project.
	 * @param saveFile path to the savefile of the new project.
	 * @param imageSet the initial set of images/pages, or {@code null}.
	 * @param pipelines the initial set of pipelines, or {@code null}.
	 * @param defaultPipeline the default pipeline (an object included in the
	 * initial set of pipelines!), or {@code null}.
	 * @return the thread doing the job.
	 */
	public Thread newProject(String name, Path saveFile, List<Path> imageSet, List<PipelineData.Pipeline> pipelines, PipelineData.Pipeline defaultPipeline) {
		if (hasProject()) {
			closeProject();
		}

		BackgroundTask<Void> task = new BackgroundTask<Void>(
				uiStrategy,
				eventBus
		) {
			@Override
			protected Void call() throws Exception {
				updateTitle(localize("project.new") + "...");
				updateMessage(localize("project.creating") + "...");
				updateProgress(-1, Double.NaN);

				final ProjectData projectData = new ProjectData(
						name,
						saveFile,
						imageSet,
						pipelines
				);
				final int defaultId = (defaultPipeline == null) ? -1 : defaultPipeline.id;
				if (defaultId >= 0) {
					for (ProjectData.Page page : projectData.getPages()) {
						page.pipelineId = defaultId;
					}
				}

				project = Project.newProject(projectData, ApplicationHandler.this);
				project.save();

				return null;
			}

			@Override
			protected void cleanUp() {
				cleanUpProject();
			}

			@Override
			protected void succeeded() {
				runLater(() -> {
					initProject();
					eventBus.post(new ProjectNotification(
							ProjectNotification.Type.OPENED
					));
					eventBus.post(new StatusMessageEvent(
							localize("project.created")
					));
				});
				super.succeeded();
			}
		};
		return task.start();
	}

	/**
	 * Opens a project.
	 *
	 * @param saveFile the savefile of the project.
	 * @return the thread doing the job.
	 */
	public Thread openProject(Path saveFile) {
		if (hasProject()) {
			closeProject();
		}

		BackgroundTask<ProjectData> task = new BackgroundTask<ProjectData>(
				uiStrategy,
				eventBus
		) {
			@Override
			protected ProjectData call() throws Exception {
				updateTitle(localize("project.open") + "...");
				updateMessage(localize("project.open") + "...");
				updateProgress(-1, Double.NaN);

				return loadProjectData(saveFile);
			}

			@Override
			protected void cleanUp() {
				final ProjectData data = getValue();
				if (data != null) {
					final ZipFileSystem zip = data.zip;
					if (zip != null) {
						try {
							zip.close();
						} catch (IOException ex) {
							log.warn("closing zip failed: {}", zip, ex);
						}
					}
				}
				cleanUpProject();
			}

			@Override
			protected void failed() {
				// try to repair project data
				cleanUp();
				// ...or show error if unfixable
				uiStrategy.showError(this.getException());
				super.failed();
			}

			@Override
			protected void succeeded() {
				final ProjectData.ValidationResult validation = getValue().validate(
						ApplicationHandler.this
				);

				if (!validation.isValid()) {
					log.debug("failed to open project: {}", getValue());
					log.debug("invalid/corrupt project data: {}", validation);

					projectData = getValue();
					projectValidation = validation;

					runLater(() -> {
						eventBus.post(new ProjectRequest(ProjectRequest.Type.REPAIR));
					});
				} else {
					project = Project.openProject(getValue(), ApplicationHandler.this);
					broadcastOpenProject();
				}
				super.succeeded();
			}
		};
		return task.start();
	}

	/**
	 * Opens a project given already validated project data. Used to load a
	 * project from the command line/running headless.
	 *
	 * @param data the validated project data.
	 * @return the project.
	 */
	public Project openProject(ProjectData data) {
		if (hasProject()) {
			closeProject();
		}

		project = Project.openProject(data, ApplicationHandler.this);
		broadcastOpenProject();
		return project;
	}

	/**
	 * Loads project data.
	 *
	 * @param saveFile the savefile of the project.
	 * @return the project data.
	 * @throws IOException
	 * @throws JAXBException
	 */
	public ProjectData loadProjectData(Path saveFile) throws IOException, JAXBException {
		// tmp. working copy
		final Path zipFile = dataManager.tmpCopy(saveFile);

		final ZipFileSystem zip = ZipFileSystem.open(zipFile);
		ProjectData data;
		Exception loadingException = null;
		try (InputStream stream = new BufferedInputStream(zip.getInputStream(ProjectData.PROJECT_ROOT_XML))) {
			data = ProjectData.load(stream);
			data.file = saveFile;
			data.zipFile = zipFile;
			data.zip = zip;
		} catch (Exception ex) {
			log.error("failed to unmarshall project data: {}", saveFile, ex);
			throw (ex);
		}

		return data;
	}

	private void broadcastOpenProject() {
		Platform.runLater(() -> {
			initProject();
			eventBus.post(new ProjectNotification(
					ProjectNotification.Type.OPENED
			));
			eventBus.post(new StatusMessageEvent(
					localize("project.opened")
			));
		});
	}

	/**
	 * Checks whether there is temporary data of a damaged or repaired project,
	 * or not.
	 *
	 * @return {@code true} if temporary data of a damaged or repaired project
	 * is around, {@code false} otherwise.
	 */
	public boolean hasRepairData() {
		return (this.projectData != null && this.projectValidation != null);
	}

	/**
	 * Returns the temporary project data of a damaged or repaired project.
	 *
	 * @return temporary project data of a damaged or repaired project, or
	 * {@code null}.
	 */
	public ProjectData getRepairData() {
		return this.projectData;
	}

	/**
	 * Returns the validation result associated to the temporary project data of
	 * a damaged or repaired project.
	 *
	 * @return the validation result associated to the temporary project data of
	 * a damaged or repaired project, or {@code null}.
	 */
	public ProjectData.ValidationResult getRepairValidation() {
		return this.projectValidation;
	}

	/**
	 * Manually validates project data.
	 *
	 * @param data the project data.
	 * @return the validation result.
	 */
	public ProjectData.ValidationResult validateProjectData(ProjectData data) {
		this.projectData = data;
		this.projectValidation = data.validate(this);
		return projectValidation;
	}

	/**
	 * Deletes repaired project data.
	 */
	public void clearRepairData() {
		this.projectData = null;
		this.projectValidation = null;
	}

	/**
	 * Opens a project from the repaired project data.
	 */
	public void openRepairedProject() {
		if (!hasRepairData()) {
			return;
		}
		this.project = Project.openProject(this.projectData, ApplicationHandler.this);
		this.project.modifiedProperty().set(true);
		broadcastOpenProject();
	}

	/**
	 * Saves the current project.
	 *
	 * @return the thread doing the job, or {@code null} if there is no project
	 * to be saved.
	 */
	public BackgroundTask<Integer> saveProject() {
		if (!hasProject()) {
			return null;
		}

		BackgroundTask<Integer> task = new BackgroundTask<Integer>(
				uiStrategy,
				eventBus
		) {
			@Override
			protected Integer call() throws Exception {
				updateTitle(localize("project.save"));
				updateMessage(localize("project.save"));
				updateProgress(-1, Double.NaN);

				project.save();
				return 0;
			}

			@Override
			protected void succeeded() {
				eventBus.post(new StatusMessageEvent(localize("project.saved")));
				super.succeeded();
			}
		};
		task.start();
		return task;
	}

	/**
	 * Saves the current project to a new file.
	 *
	 * @param saveFile path to the new save file.
	 * @return the thread doing the job, or {@code null} if there is no project
	 * to be saved.
	 */
	public Thread saveAsProject(Path saveFile) {
		if (!hasProject()) {
			return null;
		}

		BackgroundTask<Integer> task = new BackgroundTask<Integer>(
				uiStrategy,
				eventBus
		) {
			@Override
			protected Integer call() throws Exception {
				updateTitle(localize("project.save"));
				updateMessage(localize("project.save"));
				updateProgress(-1, Double.NaN);

				project.saveAs(saveFile);
				return 0;
			}

			@Override
			protected void succeeded() {
				eventBus.post(new StatusMessageEvent(localize("project.saved")));
				super.succeeded();
			}
		};
		return task.start();
	}

	/**
	 * Inits/hooks up a project after creating a new one or loading a saved one.
	 */
	private void initProject() {
		if (project == null) {
			return;
		}

		// wire up project
		hasProjectProperty.set(true);
		modifiedProjectProperty.bind(project.modifiedProperty().getObservableValue());
		selectedPageProperty.bind(project.selectedPageIdProperty());
		canProcessPageProperty.bind(project.canProcessSelectedPageProperty());
	}

	/**
	 * Closes the current project.
	 */
	public void closeProject() {
		if (!hasProject()) {
			return;
		}

		// broadcast: pre-close
		eventBus.post(new ProjectNotification(ProjectNotification.Type.CLOSING));

		cleanUpProject();

		modifiedProjectProperty.unbind();
		modifiedProjectProperty.set(false);
		selectedPageProperty.unbind();
		selectedPageProperty.set(-1);
		canProcessPageProperty.unbind();
		canProcessPageProperty.set(false);

		// broadcast: post-close
		eventBus.post(new ProjectNotification(ProjectNotification.Type.CLOSED));
	}

	/**
	 * Cleans up/frees project resources. Called upon closing a project, or
	 * after failing to load one.
	 */
	private void cleanUpProject() {
		if (project != null) {
			project.close();
		}
		project = null;
		projectData = null;
		projectValidation = null;
		hasProjectProperty.set(false);
	}

	/**
	 * Returns the current project.
	 *
	 * @return the current project, or {@code null}.
	 */
	public Project getProject() {
		return project;
	}

	/**
	 * Checks whether a project is opened or not.
	 *
	 * @return {@code true} if a project is opened, {@code false} otherwise.
	 */
	public boolean hasProject() {
		return hasProjectProperty.get();
	}

	/**
	 * The has project property. This property is {@code true} if a project is
	 * opened, {@code false} otherwise.
	 *
	 * @return the has project property.
	 */
	public ReadOnlyBooleanProperty hasProjectProperty() {
		return hasProjectProperty.getReadOnlyProperty();
	}

	/**
	 * The selected page property. Holds the index of the selected page, or -1.
	 *
	 * @return the selected page property.
	 */
	public ReadOnlyIntegerProperty selectedPageProperty() {
		return selectedPageProperty.getReadOnlyProperty();
	}

	/**
	 * The canProcessPage property. {@code true} if the selected page can be
	 * (further) processed.
	 *
	 * @return the canProcessPage property.
	 */
	public ReadOnlyBooleanProperty canProcessPageProperty() {
		return canProcessPageProperty.getReadOnlyProperty();
	}

	/**
	 * The modified property.
	 *
	 * @return the modified property.
	 */
	public ReadOnlyBooleanProperty modifiedProjectProperty() {
		return modifiedProjectProperty.getReadOnlyProperty();
	}

	/**
	 * Checks whether the project has been modified since opening.
	 *
	 * @return {@code true} if the project has been modified, {@code false}
	 * otherwise.
	 */
	public boolean isProjectModified() {
		if (!hasProject()) {
			return false;
		}

		return modifiedProjectProperty.get();
	}

	/**
	 * Returns the filename of the project.
	 *
	 * @return the filename of the project.
	 */
	public String getProjectFileName() {
		return project.getFilename();
	}

	/**
	 * Writes application settings back to disk.
	 *
	 * @return {@code true} in case of success, {@code false} otherwise.
	 */
	public boolean saveUserSettings() {
		try {
			settings.save(dataManager.appDataDir.settingsFile);
			return true;
		} catch (JAXBException ex) {
			log.error(
					"error writing user settings back to disk: {}",
					dataManager.appDataDir.settingsFile,
					ex
			);
			return false;
		}
	}

}
