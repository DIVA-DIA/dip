package ch.unifr.diva.dip.core.model;

import ch.unifr.diva.dip.core.execution.PipelineExecutionController;
import ch.unifr.diva.dip.core.ApplicationHandler;
import ch.unifr.diva.dip.core.ui.Localizable;
import ch.unifr.diva.dip.core.ui.UIStrategy.Answer;
import ch.unifr.diva.dip.eventbus.events.ProjectNotification;
import ch.unifr.diva.dip.utils.Modifiable;
import ch.unifr.diva.dip.utils.ModifiedProperty;
import ch.unifr.diva.dip.gui.pe.PipelineEditor;
import ch.unifr.diva.dip.api.utils.FxUtils;
import ch.unifr.diva.dip.core.execution.PipelineExecutionDialog;
import ch.unifr.diva.dip.core.execution.PipelineExecutionLogger;
import ch.unifr.diva.dip.eventbus.events.StatusMessageEvent;
import ch.unifr.diva.dip.utils.BackgroundTask;
import ch.unifr.diva.dip.utils.CursorLock;
import ch.unifr.diva.dip.utils.ZipFileSystem;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Cursor;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A project is a list of pages.
 */
public class Project implements Modifiable, Localizable {

	private static final Logger log = LoggerFactory.getLogger(Project.class);

	/**
	 * The suffix (placed after the project name) for the directory to export
	 * project files to.
	 */
	public static final String EXPORT_DIRECTORY_SUFFIX = "-out";

	private final ApplicationHandler handler;

	/**
	 * Original savefile of the project. This file is generally not
	 * opened/loaded as a zip filesystem. We're working with a temp. working
	 * copy of this file that gets copied back to this file upon saving the
	 * project.
	 */
	private Path file;

	/**
	 * Temporary working copy of {@code file}, the original savefile.
	 */
	private final Path zipFile;

	/**
	 * The (opened) zip file system holding the contents of {@code zipFile},
	 * which is a working copy of {@code file}, the original savefile. This
	 * means that we can always make this file system dirty, remove and
	 * overwrite files or what not, without having to fear that we messed up the
	 * original savefile, e.g. in case the user doesn't really want to save his
	 * work.
	 */
	private ZipFileSystem zip;

	/**
	 * Pipeline manager holding the project's processing pipelines. Each page is
	 * assigned to one such pipeline, and while a page still might overwrite
	 * some parameters of certain processors the structure of the pipline itself
	 * can not be altered (on a per page level).
	 */
	private final PipelineManager pipelineManager;

	private int maxPageId = 0;
	private final StringProperty projectNameProperty = new SimpleStringProperty();
	private final ModifiedProperty modifiedProjectProperty = new ModifiedProperty();
	private final IntegerProperty selectedPageIdProperty = new SimpleIntegerProperty(-1);
	private final BooleanProperty canProcessSelectedPageProperty = new SimpleBooleanProperty(false);
	private final ObservableList<ProjectPage> pages = FXCollections.observableArrayList();

	/**
	 * Factory method to create a new project.
	 *
	 * @param data initial project data.
	 * @param handler
	 * @return a project.
	 * @throws Exception in case of I/O errors.
	 */
	public static Project newProject(ProjectData data, ApplicationHandler handler) throws Exception {
		data.zipFile = handler.dataManager.tmpFile(true);
		final ZipFileSystem zip = ZipFileSystem.create(data.zipFile);

		FxUtils.run(() -> {
			handler.settings.recentFiles.setSaveDirectory(data.file);
		});

		try (OutputStream stream = new BufferedOutputStream(zip.getOutputStream(ProjectData.PROJECT_ROOT_XML))) {
			data.save(stream);
			data.zip = zip;
		} catch (Throwable throwable) {
			throw (throwable);
		}

		return new Project(data, handler);
	}

	/**
	 * Factory method to open an existing project.
	 *
	 * @param data project data assumed to be already validated.
	 * @param handler
	 * @return a project.
	 */
	public static Project openProject(ProjectData data, ApplicationHandler handler) {
		FxUtils.run(() -> {
			handler.settings.recentFiles.setSaveDirectory(data.file);
		});

		return new Project(data, handler);
	}

	/**
	 * Default constructor.
	 *
	 * @param data project data (with an open zip filesystem).
	 * @param handler
	 */
	private Project(ProjectData data, ApplicationHandler handler) {
		this.handler = handler;
		this.file = data.file;
		this.zipFile = data.zipFile;
		this.zip = data.zip;

		// read/parse project data
		projectNameProperty.set(data.name);

		// pipelines
		pipelineManager = new PipelineManager(
				this.handler,
				data.pipelines(),
				data.defaultPipeline
		);

		// images -> pages
		for (ProjectData.Page image : data.getPages()) {
			if (image.id > maxPageId) {
				maxPageId = image.id;
			}
			addPage(new ProjectPage(this, image), false);
		}

		// hook up properties, attach listeners (for modifications, ...)
		selectPage(data.getSelectedPage());

		// listen to properties for modifications
		modifiedProjectProperty.addObservedProperty(projectNameProperty);
		modifiedProjectProperty.addObservedProperty(selectedPageIdProperty);

		// listen to (and manage) modifiables for modifications
		modifiedProjectProperty.addManagedProperty(pipelineManager);
	}

	/**
	 * Closes the project and its resources.
	 */
	public void close() {
		closePage();

		if (zip != null) {
			try {
				zip.close();
			} catch (IOException ex) {
				log.warn("closing zip failed: {}", zip, ex);
			}
		}
	}

	/**
	 * Closes the currently selected page.
	 */
	public void closePage() {
		final ProjectPage currentPage = getSelectedPage();
		if (currentPage != null) {
			currentPage.close();
		}
		unbindSelectedPage();
	}

	private void unbindSelectedPage() {
		// might run on a background thread, so make sure we only touch props
		// and bindings on the FX Application thread
		FxUtils.runAndWait(() -> {
			selectedPageIdProperty.set(-1);
			canProcessSelectedPageProperty.unbind();
			canProcessSelectedPageProperty.set(false);
		});
	}

	/**
	 * Saves the project.
	 *
	 * @return the saved project data.
	 * @throws Exception in case of an I/O error.
	 */
	public ProjectData save() throws Exception {
		return saveInternal();
	}

	/**
	 * Sets the file (or savefile) of the project. The original savefile is left
	 * untouched (even if marked as modified), while a new copy of the project
	 * is created at the given location.
	 *
	 * @param file path to the new project file.
	 * @return the saved project data.
	 * @throws Exception in case of an I/O error.
	 */
	public ProjectData saveAs(Path file) throws Exception {
		this.file = file;
		FxUtils.run(() -> {
			this.handler.settings.recentFiles.setSaveDirectory(this.file);
		});
		return saveInternal();
	}

	/**
	 * Internal save method used by {@code save} and {@code saveAs} methods.
	 *
	 * @return the saved project data.
	 * @throws Exception in case of an I/O error.
	 */
	private ProjectData saveInternal() throws Exception {
		final ProjectData data = new ProjectData(this);

		// save current page and its pipeline (e.g. the object map)
		if (getSelectedPageId() > 0 && getSelectedPage() != null) {
			getSelectedPage().save();
		}

		// write project root xml
		Files.deleteIfExists(zip.getPath(ProjectData.PROJECT_ROOT_XML));

		try (OutputStream stream = new BufferedOutputStream(zip.getOutputStream(ProjectData.PROJECT_ROOT_XML))) {
			data.save(stream);
		} catch (Throwable throwable) {
			throw (throwable);
		}

		// write pipeline data
		Files.deleteIfExists(zip.getPath(ProjectData.PROJECT_PIPELINES_XML));
		try (OutputStream stream = new BufferedOutputStream(zip.getOutputStream(ProjectData.PROJECT_PIPELINES_XML))) {
			pipelineManager.exportPipelines(stream);
		} catch (Throwable throwable) {
			throw (throwable);
		}

		// copy tmp. working copy back to original file
		zip.close();
		Files.copy(zipFile, file, StandardCopyOption.REPLACE_EXISTING);
		zip = ZipFileSystem.open(zipFile);

		// context switch for all open processors (we need to update all refs/
		// paths to the reopened zip filesystem.
		final ProjectPage currentPage = getSelectedPage();
		if (currentPage != null) {
			final RunnablePipeline currentPipeline = currentPage.getPipeline();
			if (currentPipeline != null) {
				currentPipeline.contextSwitch();
			}
		}

		// mark project (and managed modifiables) as clean/unmodified
		modifiedProjectProperty.set(false);
		return data;
	}

	/**
	 * Returns the file (or savefile) of the project.
	 *
	 * @return path to the project file.
	 */
	public Path getFile() {
		return this.file;
	}

	/**
	 * Returns the (opened) zip file system (or savefile) of the project.
	 *
	 * @return the zip file system of the project.
	 */
	public ZipFileSystem zipFileSystem() {
		return this.zip;
	}

	/**
	 * Sets the name of the project.
	 *
	 * @param name the name of the project.
	 */
	public void setProjectName(String name) {
		projectNameProperty.set(name);
	}

	/**
	 * Returns the name of the project.
	 *
	 * @return the name of the project.
	 */
	public String getProjectName() {
		return projectNameProperty.get();
	}

	/**
	 * Property of the project name.
	 *
	 * @return projectNameProperty.
	 */
	public StringProperty projectNameProperty() {
		return projectNameProperty;
	}

	/**
	 * Returns the filename of the projects savefile.
	 *
	 * @return the projects filename.
	 */
	public String getFilename() {
		final Path p = file.getFileName();
		if (p == null) {
			return "";
		}
		return p.toString();
	}

	/**
	 * Returns the path to the project's export directory. This directory is for
	 * data that a processor can store publicly (i.e. not as state inside the
	 * dip file).
	 *
	 * @return the path to the project's export directory.
	 */
	public Path getExportDirectory() {
		final Path parent = file.getParent();
		if (parent == null) {
			return null;
		}
		return parent.resolve(getFilename() + EXPORT_DIRECTORY_SUFFIX);
	}

	/**
	 * Returns the pages of the project.
	 *
	 * @return a list of all pages of the project.
	 */
	public ObservableList<ProjectPage> pages() {
		return pages;
	}

	/**
	 * Returns the first page of the project.
	 *
	 * @return the first project page.
	 */
	public ProjectPage getFirstPage() {
		if (pages.isEmpty()) {
			return null;
		}
		return pages.get(0);
	}

	/**
	 * Returns the id of the first page of the project.
	 *
	 * @return id of the first project page.
	 */
	public int getFirstPageId() {
		if (pages.isEmpty()) {
			return -1;
		}
		return pages.get(0).id;
	}

	/**
	 * Returns the project's pipeline manager.
	 *
	 * @return the project's pipeline manager.
	 */
	public PipelineManager pipelineManager() {
		return pipelineManager;
	}

	/**
	 * Returns the application handler.
	 *
	 * @return the application handler.
	 */
	protected ApplicationHandler applicationHandler() {
		return handler;
	}

	/**
	 * Shows/hides the project's pipeline editor.
	 *
	 * @param stage the parent stage.
	 * @param show {@code true} to show, {@code false} to hide the pipeline
	 * editor.
	 */
	public void openPipelineEditor(Stage stage, boolean show) {
		final PipelineEditor ed = getPipelineEditor(stage);

		if (show) {
			ed.show();
		} else {
			ed.close();
		}
	}

	/**
	 * Returns the pipeline editor.
	 *
	 * @param stage the parent stage.
	 * @return the pipeline editor.
	 */
	public PipelineEditor getPipelineEditor(Stage stage) {
		/*
		 * Always(!) return a new pipeline editor, which first retrieves a
		 * backup of all pipelines, s.t. we're able to restore them if needed.
		 */
		return new PipelineEditor(stage, handler);
	}

	/**
	 * Property of the currently selected page id.
	 *
	 * @return selectedPageIdProperty.
	 */
	public ReadOnlyIntegerProperty selectedPageIdProperty() {
		return selectedPageIdProperty;
	}

	/**
	 * The canProcessSelectedPage property. {@code true} if the selected page
	 * can be (further) processed.
	 *
	 * @return the canProcessSelectedPageProperty.
	 */
	public ReadOnlyBooleanProperty canProcessSelectedPageProperty() {
		return this.canProcessSelectedPageProperty;
	}

	/**
	 * Selects a project page by id.
	 *
	 * @param id an id of a project page.
	 * @return {@code true} if a new page has been selected, {@code false} if
	 * not (e.g.\ if the page already was selected).
	 */
	public final boolean selectPage(int id) {
		final int selected = getSelectedPageId();
		if (selected == id || id < 0) {
			return false;
		}

		if (selected >= 0) {
			closePage();
		}

		final ProjectPage page = getPage(id);
		if (page != null) {
			page.open();
			// might run on a background thread, so make sure we only touch props
			// and bindings on the FX Application thread
			FxUtils.runAndWait(() -> {
				selectedPageIdProperty.set(id);
				canProcessSelectedPageProperty.bind(page.canProcessProperty());
			});
		}
		return true;
	}

	/**
	 * Returns the id of the currently selected project page.
	 *
	 * @return id of the selected project page.
	 */
	public int getSelectedPageId() {
		if (!Platform.isFxApplicationThread()) {
			try {
				return FxUtils.runFutureTask(() -> {
					return selectedPageIdProperty.get();
				});
			} catch (Exception ex) {
				// return from property
			}
		}
		return selectedPageIdProperty.get();
	}

	/**
	 * Returns the currently selected page.
	 *
	 * @return the selected project page or {@code null} if no page is selected.
	 */
	public ProjectPage getSelectedPage() {
		if (getSelectedPageId() < 0) {
			return null;
		}

		return getPage(getSelectedPageId());
	}

	/**
	 * Returns a project page by id.
	 *
	 * @param id id of a project page.
	 * @return a project page - or {@code null} if not found.
	 */
	public ProjectPage getPage(int id) {
		for (ProjectPage page : pages) {
			if (id == page.id) {
				return page;
			}
		}
		return null;
	}

	/**
	 * Returns all pages with the specified pipeline assignment.
	 *
	 * @param pipelineId the pipeline id.
	 * @return all pages that use the specified pipeline.
	 */
	public List<ProjectPage> getPages(int pipelineId) {
		final List<ProjectPage> list = new ArrayList<>();
		for (ProjectPage page : pages) {
			if (page.getPipelineId() == pipelineId) {
				list.add(page);
			}
		}
		return list;
	}

	/**
	 * Adds a page to the project.
	 *
	 * @param file the file of the page.
	 * @throws IOException
	 */
	public void addPage(File file) throws IOException {
		addPage(file.toPath());
	}

	/**
	 * Adds a page to the paroject.
	 *
	 * @param file the path to the file of the page.
	 * @throws IOException
	 */
	public void addPage(Path file) throws IOException {
		addPage(new ProjectPage(this, file));
	}

	/**
	 * Adds a page to the project.
	 *
	 * @param page the new project page.
	 */
	final public void addPage(ProjectPage page) {
		addPage(page, true);
	}

	/**
	 * Adds a page to the project.
	 *
	 * @param page the new project page.
	 * @param setModified {@code true} to mark the project as modified,
	 * {@code false} otherwise.
	 */
	final public void addPage(ProjectPage page, boolean setModified) {
		this.modifiedProjectProperty.addManagedProperty(page);
		addToPipelineUsage(page.getPipelineId(), 1);
		page.pipelineIdProperty().addListener(pipelineUsageListener);
		pages.add(page);

		if (setModified) {
			this.modifiedProperty().set(true);
		}
	}

	// pipeline usage by pages; maps pipelineId -> usage counter
	private final Map<Integer, IntegerProperty> pipelineUsageMap = new HashMap<>();
	private final ChangeListener<Number> pipelineUsageListener = (obs, oldId, newId) -> {
		if (oldId != null) {
			addToPipelineUsage(oldId.intValue(), -1);
		}
		addToPipelineUsage(newId.intValue(), 1);
	};

	// to increment or decrement the pipeline usage counter
	private void addToPipelineUsage(int id, int value) {
		final IntegerProperty p = pipelineUsageProperty(id);
		p.set(p.get() + value);
	}

	/**
	 * The pipeline usage property. Counts the number of times a pipeline is
	 * used by a page.
	 *
	 * @param id the pipeline id.
	 * @return the pipeline usage property.
	 */
	public IntegerProperty pipelineUsageProperty(int id) {
		if (!pipelineUsageMap.containsKey(id)) {
			final IntegerProperty p = new SimpleIntegerProperty(0);
			pipelineUsageMap.put(id, p);
			return p;
		}
		return pipelineUsageMap.get(id);
	}

	/**
	 * Returns the number of times a pipeline is used by pages.
	 *
	 * @param id the pipeline id.
	 * @return the number of times a pipeline is used by pages.
	 */
	public int getPipelineUsage(int id) {
		if (!pipelineUsageMap.containsKey(id)) {
			return 0;
		}
		return pipelineUsageMap.get(id).get();
	}

	/**
	 * Removes a page from the project.
	 *
	 * @param id the id of the page.
	 */
	public void deletePage(int id) {
		deletePage(getPage(id));
	}

	/**
	 * Remoces a page from the project.
	 *
	 * @param page the project page.
	 */
	public void deletePage(ProjectPage page) {
		final List<ProjectPage> list = Arrays.asList(page);
		deletePages(list);
	}

	/**
	 * Removes a list of pages from the project, but asks for confirmation
	 * first.
	 *
	 * @param selection the list of pages.
	 */
	public void deletePages(List<ProjectPage> selection) {
		deletePages(selection, true);
	}

	/**
	 * Removes a list of pages from the project.
	 *
	 * @param selection the list of pages.
	 * @param confirm {@code true} to ask for confirmation first, {@code false}
	 * to directly remove the pages, no questions asked.
	 */
	public void deletePages(List<ProjectPage> selection, boolean confirm) {
		if (confirm) {
			final List<String> names = new ArrayList<>();
			for (ProjectPage page : selection) {
				names.add(page.getName());
			}
			final String msg = localize("delete.confirm", String.join(", ", names));
			final Answer answer = handler.uiStrategy.getAnswer(msg);

			switch (answer) {
				case YES:
					break;
				case NO:
				case CANCEL:
					return;
			}
		}

		for (ProjectPage page : selection) {
			if (!pages.contains(page)) {
				continue;
			}

			log.info("deleting page: {}", page);
			if (page.id == getSelectedPageId()) {
				unbindSelectedPage();
				handler.eventBus.post(
						new ProjectNotification(ProjectNotification.Type.SELECTED, -1)
				);
			}
			page.pipelineIdProperty().removeListener(pipelineUsageListener);
			addToPipelineUsage(page.getPipelineId(), -1);
			this.modifiedProjectProperty.removeManagedProperty(page);
			pages.remove(page);
			page.clear();
			this.handler.eventBus.post(
					new ProjectNotification(ProjectNotification.Type.PAGE_REMOVED, page.id)
			);
		}

		this.modifiedProperty().set(true);
	}

	/**
	 * Returns a new, unique page id.
	 *
	 * @return the new page id.
	 */
	protected int newPageId() {
		maxPageId++;
		return maxPageId;
	}

	/**
	 * Processes the page with the given id, or all pages with {@code -1}.
	 *
	 * @param pageId the page id, or {@code -1}.
	 * @return the started background task.
	 */
	public BackgroundTask<Void> processPage(int pageId) {
		if (pageId < 0) {
			return processAllPages();
		}
		return processPages(Arrays.asList(getPage(pageId)));
	}

	/**
	 * Processes all pages.
	 *
	 * @return the started background task.
	 */
	public BackgroundTask<Void> processAllPages() {
		return processPages(pages(), null);
	}

	/**
	 * Processes all pages.
	 *
	 * @param logger the pipeline execution logger.
	 * @return the started background task.
	 */
	public BackgroundTask<Void> processAllPages(PipelineExecutionLogger logger) {
		return processPages(pages(), logger);
	}

	/**
	 * Processes the given pages.
	 *
	 * @param pages the pages to process.
	 * @return the started background task.
	 */
	public BackgroundTask<Void> processPages(List<ProjectPage> pages) {
		return processPages(pages, null);
	}

	/**
	 * Processes the given pages.
	 *
	 * @param pages the pages to process.
	 * @param logger the pipeline execution logger.
	 * @return the started background task.
	 */
	public BackgroundTask<Void> processPages(List<ProjectPage> pages, PipelineExecutionLogger logger) {
		final CursorLock cursorLock = new CursorLock(handler, Cursor.WAIT);
		final PipelineExecutionController controller;
		if (logger == null) {
			controller = new PipelineExecutionController(handler, pages);
		} else {
			controller = new PipelineExecutionController(handler, logger, pages);
		}
		final BackgroundTask<Void> task = new BackgroundTask<Void>(handler) {
			@Override
			protected Void call() throws Exception {
				updateTitle(localize("processing.object", localize("pipelines")) + "...");
				updateMessage(localize("processing.object", localize("pipelines")) + "...");
				updateProgress(-1, Double.NaN);
				controller.process();
				return null;
			}

			@Override
			protected void finished(BackgroundTask.Result result) {
				runLater(() -> {
					handler.eventBus.post(new StatusMessageEvent(
							localize("processing.object", localize("pipelines")) + "..."
							+ " "
							+ localize("done")
							+ "."
					));
					cursorLock.stop();
				});
			}
		};
		task.start();
		if (handler.uiStrategy.hasStage()) {
			final PipelineExecutionDialog dialog = controller.getDialog(handler.uiStrategy.getStage());
			dialog.show();
		}
		return task;
	}

	/**
	 * Resets the page with the given id, or all pages with {@code -1}.
	 *
	 * @param pageId the page id, or {@code -1}.
	 * @return the started background task.
	 */
	public BackgroundTask<Void> resetPage(int pageId) {
		if (pageId < 0) {
			return resetAllPages();
		} else {
			return resetPages(Arrays.asList(getPage(pageId)));
		}
	}

	/**
	 * Resets all pages.
	 *
	 * @return the started background task.
	 */
	public BackgroundTask<Void> resetAllPages() {
		return resetPages(pages());
	}

	private BackgroundTask<Void> resetPages(List<ProjectPage> pages) {
		final CursorLock cursorLock = new CursorLock(handler, Cursor.WAIT);
		final BackgroundTask<Void> task = new BackgroundTask<Void>(handler) {

			@Override
			protected Void call() throws Exception {
				updateTitle(localize("resetting.object", localize("pipelines")) + "...");
				final int n = pages.size();
				int i = 1;
				for (ProjectPage page : pages) {
					updateMessage(localize("resetting.object", page.getName()) + "...");
					if (i == n) {
						updateProgress(-1, Double.NaN);
					} else {
						updateProgress(i++, n);
					}

					final boolean doOpen = !page.isOpened();
					if (doOpen) {
						page.open();
					}
					page.reset();
					runLater(() -> modifiedProperty().set(true));
					if (doOpen) {
						page.close(false);
					}
				}
				return null;
			}

			@Override
			protected void finished(BackgroundTask.Result result) {
				runLater(() -> {
					handler.eventBus.post(new StatusMessageEvent(
							localize("resetting.object", localize("pipelines")) + "..."
							+ " "
							+ localize("done")
							+ "."
					));
					cursorLock.stop();
				});
			}
		};
		task.start();
		return task;
	}

	@Override
	public ModifiedProperty modifiedProperty() {
		return modifiedProjectProperty;
	}

	@Override
	public String toString() {
		return this.getClass().getSimpleName()
				+ "{"
				+ "name=" + this.getProjectName()
				+ ", pages=" + pages.size()
				+ "}";
	}

}
