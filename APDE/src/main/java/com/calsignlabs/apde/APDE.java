package com.calsignlabs.apde;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.preference.PreferenceManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.calsignlabs.apde.support.documentfile.DocumentFile;
import androidx.multidex.MultiDexApplication;

import android.provider.DocumentsContract;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.calsignlabs.apde.build.ComponentTarget;
import com.calsignlabs.apde.build.Manifest;
import com.calsignlabs.apde.build.SketchProperties;
import com.calsignlabs.apde.build.dag.BuildContext;
import com.calsignlabs.apde.build.dag.ModularBuild;
import com.calsignlabs.apde.contrib.Library;
import com.calsignlabs.apde.support.AndroidPlatform;
import com.calsignlabs.apde.support.MaybeDocumentFile;
import com.calsignlabs.apde.task.TaskManager;
import com.calsignlabs.apde.tool.AutoFormat;
import com.calsignlabs.apde.tool.ColorSelector;
import com.calsignlabs.apde.tool.CommentUncomment;
import com.calsignlabs.apde.tool.DecreaseIndent;
import com.calsignlabs.apde.tool.ExportSignedPackage;
import com.calsignlabs.apde.tool.FindInReference;
import com.calsignlabs.apde.tool.FindReplace;
import com.calsignlabs.apde.tool.GitManager;
import com.calsignlabs.apde.tool.ImportLibrary;
import com.calsignlabs.apde.tool.IncreaseIndent;
import com.calsignlabs.apde.tool.ManageLibraries;
import com.calsignlabs.apde.tool.ManageWallpapers;
import com.calsignlabs.apde.tool.OpenReference;
import com.calsignlabs.apde.tool.Tool;
import com.calsignlabs.apde.tool.UninstallSketch;
import com.calsignlabs.apde.vcs.GitRepository;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.security.ProviderInstaller;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import processing.app.Platform;

/**
 * This is the Application global state for APDE. It manages things like the
 * currently selected sketch and references to the various activities.
 */
public class APDE extends MultiDexApplication {
	public static final String LIBRARIES_FOLDER = "libraries";
	
	public static final String DEFAULT_SKETCH_TAB = "sketch";
	
	public static final String LAST_TEMPORARY_SKETCH_NAME_PREF = "last_temporary_sketch_name";
	
	public static final String EXAMPLES_REPO = "https://github.com/Calsign/APDE-Examples-Repo.git";
	// We use a new branch every time we break backwards-compatibility by updating the examples
	public static final String EXAMPLES_REPO_BRANCH = "v0.5.0";
	
	private TaskManager taskManager;
	
	private String sketchName;
	
	private EditorActivity editor;
	private SketchPropertiesActivity propertiesActivity;
	
	private Map<String, List<Library>> importToLibraryTable = Collections.synchronizedMap(new HashMap<>());
	private List<Library> contributedLibraries;
	
	private Map<String, Tool> packageToToolTable;
	private List<Tool> tools;
	
	private ModularBuild modularBuild;
	
	public static final String STORAGE_DRIVE_PREF = "pref_sketchbook_drive";
	public static final String SKETCHBOOK_EXTERNAL_STORAGE_PATH_PREF = "pref_sketchbook_location";
	public static final String DEFAULT_SKETCHBOOK_LOCATION = "Sketchbook";
	public static final String SKETCHBOOK_LOCATION_SAF_PREF = "pref_sketchbook_location_saf";
	
	// Intent flag for obtaining access to the sketchbook folder on Android 11+
	public static final int FLAG_SELECT_SKETCHBOOK_FOLDER = 9;
	
	// On Android 10+ we use SAF and not the standard File API for accessing the external storage.
	// We could set the requestLegacyExternalStorage flag in the manifest to continue having the
	// legacy behavior on Android 10, but then we need to make the switch for Android 11 which
	// doesn't seem worth it. So we choose Q here, which is Android 10.
	public static final int SAF_API_CUTOFF = Build.VERSION_CODES.Q;
	
	public enum SketchLocation {
		SKETCHBOOK, // A sketch in the sketchbook folder
		EXAMPLE, // An example on the the internal storage
		LIBRARY_EXAMPLE, // An example packaged with a (contributed) library
		EXTERNAL, // A sketch located on the file system (not in the sketchbook)
		TEMPORARY; // A sketch in the temporary internal storage directory
		
		@Override
		public String toString() {
			switch (this) {
			case SKETCHBOOK:
				return "sketchbook";
			case EXAMPLE:
				return "example";
			case LIBRARY_EXAMPLE:
				return "libraryExample";
			case EXTERNAL:
				return "external";
			case TEMPORARY:
				return "temporary";
			default:
				// Uh-oh...
				return "";
			}
		}
		
		public boolean isExample() {
			switch(this) {
			case EXAMPLE:
			case LIBRARY_EXAMPLE:
				return true;
			default:
				return false;
			}
		}
		
		public boolean isTemp() {
			switch(this) {
			case TEMPORARY:
				return true;
			default:
				return false;
			}
		}
		
		public String toReadableString(Context context) {
			switch(this) {
			case SKETCHBOOK:
				return context.getResources().getString(R.string.drawer_folder_sketches);
			case EXAMPLE:
				return context.getResources().getString(R.string.drawer_folder_examples);
			case LIBRARY_EXAMPLE:
				return context.getResources().getString(R.string.drawer_folder_library_examples);
			case TEMPORARY:
				return context.getResources().getString(R.string.drawer_folder_temporary);
			default:
				return "";
			}
		}
		
		public static SketchLocation fromString(String value) {
			if (value.equals("sketchbook"))
				return SketchLocation.SKETCHBOOK;
			if (value.equals("example"))
				return SketchLocation.EXAMPLE;
			if (value.equals("libraryExample"))
				return SketchLocation.LIBRARY_EXAMPLE;
			if (value.equals("external"))
				return SketchLocation.EXTERNAL;
			if (value.equals("temporary"))
				return SketchLocation.TEMPORARY;
			
			// Strange...
			return null;
		}
	}
	
	// Location group of the sketch
	private SketchLocation sketchLocation;
	// Relative path to the sketch within its location group
	private String sketchPath;
	
	public void initTaskManager() {
		if (taskManager == null) {
			taskManager = new TaskManager(this);
		}
	}
	
	public TaskManager getTaskManager() {
		return taskManager;
	}
	
	/**
	 * Changes the name of the current sketch and updates the editor accordingly
	 * Note: This may or may not do what you think it does
	 * 
	 * @param sketchName
	 *            the new name of the sketch
	 */
	@SuppressLint("NewApi")
	public void setSketchName(String sketchName) {
		this.sketchName = sketchName;
		
		if (editor != null) {
			editor.getSupportActionBar().setTitle(sketchName);
			editor.setSaved(false);
		}
	}
	
	/**
	 * @return the name of the current sketch
	 */
	public String getSketchName() {
		return sketchName;
	}
	
	/**
	 * Select a sketch with the given location and relative path
	 * 
	 * @param sketchPath
	 * @param sketchLocation
	 */
	public void selectSketch(String sketchPath, SketchLocation sketchLocation) throws MaybeDocumentFile.MaybeDocumentFileException {
		this.sketchPath = sketchPath;
		this.sketchLocation = sketchLocation;
		
		setSketchName(getSketchLocation().getName());
	}
	
	public void selectNewTempSketch() throws MaybeDocumentFile.MaybeDocumentFileException {
		selectSketch("/" + getNextTemporarySketchName(), SketchLocation.TEMPORARY);
		putPref(LAST_TEMPORARY_SKETCH_NAME_PREF, getSketchName());
	}
	
	/**
	 * Searches a folder for valid sketches (any folder containing a .PDE file, not including subfolders of other sketches)
	 * 
	 * @param directory
	 * @param depth
	 * @return the list of sketches in the given directory
	 */
	public List<DocumentFile> listSketches(MaybeDocumentFile directory, int depth) throws MaybeDocumentFile.ResolutionException {
		// Convenience method
		return listSketches(directory, depth, new String[] {});
	}
	
	/**
	 * Searches a folder for valid sketches (any folder containing a .PDE file, not including subfolders of other sketches)
	 * 
	 * @param directory
	 * @param depth
	 * @param ignoreFilenames
	 * @return the list of sketches in the given directory
	 */
	public List<DocumentFile> listSketches(MaybeDocumentFile directory, int depth, String[] ignoreFilenames) throws MaybeDocumentFile.ResolutionException {
		// Sanity check...
		if (!directory.exists() || !directory.isDirectory()) {
			return Collections.emptyList();
		}
		
		DocumentFile resolvedDirectory = directory.resolve();
		
		// Make sure we don't want to ignore this directory
		for (String ignore : ignoreFilenames) {
			if (directory.getName() != null && directory.getName().equals(ignore)) {
				return Collections.emptyList();
			}
		}
		
		//Let's check this folder first...
		if (validSketch(directory)) {
			return Collections.singletonList(resolvedDirectory);
		}
		
		DocumentFile[] contents = resolvedDirectory.listFiles();
		ArrayList<DocumentFile> output = new ArrayList<>();
		
		// This check permits anything greater the "0" for a countdown...
		// ...or values like "-1" for infinite search depth
		if (depth != 0) {
			// Check the subfolders
			for (DocumentFile file : contents) {
				if (file.isDirectory()) { // This check is redundant, but it's here anyway
					output.addAll(listSketches(new MaybeDocumentFile(file), depth - 1, ignoreFilenames));
				}
			}
		}
		
		return output;
	}
	
	/**
	 * @param directory
	 * @return whether or not the directory contains any sketches
	 */
	public boolean containsSketches(MaybeDocumentFile directory) throws MaybeDocumentFile.ResolutionException {
		// Convenience method
		return containsSketches(directory, new String[] {});
	}
	
	/**
	 * @param directory
	 * @param ignoreFilenames
	 * @return whether or not the directory contains any sketches
	 */
	public boolean containsSketches(MaybeDocumentFile directory, String[] ignoreFilenames) throws MaybeDocumentFile.ResolutionException {
		// Sanity check...
		if (!directory.exists() || !directory.isDirectory()) {
			return false;
		}
		
		DocumentFile resolvedDirectory = directory.resolve();
		
		// Make sure we don't want to ignore this directory
		for (String ignore : ignoreFilenames) {
			if (directory.getName() != null && directory.getName().equals(ignore)) {
				return false;
			}
		}
		
		// Let's check this folder first...
		if (validSketch(directory)) {
			return true;
		}
		
		DocumentFile[] contents = resolvedDirectory.listFiles();
		
		// Check the subfolders
		for (DocumentFile file : contents) {
			if (file.isDirectory()) {
				if (containsSketches(new MaybeDocumentFile(file))) {
					return true;
				}
			}
		}
		
		return false;
	}
	
	/**
	 * @param sketchFolder
	 * @return whether or not the given folder is a valid sketch folder
	 */
	public boolean validSketch(MaybeDocumentFile sketchFolder) throws MaybeDocumentFile.ResolutionException {
		// Sanity check
		if ((!sketchFolder.exists()) || (!sketchFolder.isDirectory())) {
			return false;
		}
		
		DocumentFile[] contents = sketchFolder.resolve().listFiles();
		
		for (DocumentFile file : contents) {
			// Get the file extension
			String filename = file.getName();
			if (filename == null) {
				continue;
			}
			int lastDot = filename.lastIndexOf('.');
			String extension = lastDot != -1 ? filename.substring(lastDot) : "";
			
			// Check for .PDE
			if (extension.equalsIgnoreCase(".pde")) {
				// We have our match
				return true;
			}
		}
		
		return false;
	}
	
	public ArrayList<FileNavigatorAdapter.FileItem> listSketchContainingFolders(
			MaybeDocumentFile directory, boolean parentDraggable, boolean draggable, SketchMeta location) throws MaybeDocumentFile.ResolutionException {
		// Convenience method
		return listSketchContainingFolders(directory, new String[] {}, parentDraggable, draggable, location);
	}
	
	public ArrayList<FileNavigatorAdapter.FileItem> listSketchContainingFolders(
			MaybeDocumentFile directory, final String[] ignoreFilenames, boolean parentDroppable,
			boolean itemsDraggable, SketchMeta location) throws MaybeDocumentFile.ResolutionException {
		ArrayList<FileNavigatorAdapter.FileItem> output = new ArrayList<>();
		
		FileNavigatorAdapter.FileItem emptyItem =
				new FileNavigatorAdapter.FileItem(getResources().getString(R.string.drawer_folder_empty),
						FileNavigatorAdapter.FileItemType.MESSAGE);
		
		// Add the "navigate up" button
		output.add(new FileNavigatorAdapter.FileItem(FileNavigatorAdapter.NAVIGATE_UP_TEXT,
				FileNavigatorAdapter.FileItemType.NAVIGATE_UP, false, parentDroppable,
				new SketchMeta(location.getLocation(), location.getParent())));
		
		// Sanity check...
		if (directory == null || !directory.exists() || !directory.isDirectory()) {
			// Let the user know that the folder is empty...
			output.add(emptyItem);
			return output;
		}
		
		DocumentFile resolvedDirectory = directory.resolve();
		
		Set<String> ignored = new HashSet<>(Arrays.asList(ignoreFilenames));
		
		DocumentFile[] contents = resolvedDirectory.listFiles();
		
		// Cycle through the files
		for (DocumentFile file : contents) {
			if (file.getName() == null || ignored.contains(file.getName())) {
				continue;
			}
			
			// Check to see if this folder has anything worth our time
			if (validSketch(new MaybeDocumentFile(file))) {
				output.add(new FileNavigatorAdapter.FileItem(file.getName(),
						FileNavigatorAdapter.FileItemType.SKETCH, itemsDraggable, false,
						new SketchMeta(location.getLocation(), location.getPath() + "/" + file.getName())));
			} else if (containsSketches(new MaybeDocumentFile(file))) {
				output.add(new FileNavigatorAdapter.FileItem(file.getName(),
						FileNavigatorAdapter.FileItemType.FOLDER, itemsDraggable, itemsDraggable,
						new SketchMeta(location.getLocation(), location.getPath() + "/" + file.getName())));
			}
		}
		
		// If only navigate up button, then it's empty
		if (output.size() == 1) {
			output.add(emptyItem);
			return output;
		}
		
		// Sort the output alphabetically with folders on top
		Collections.sort(output, (one, two) -> {
			if (one.getType().equals(two.getType())) {
				return one.getText().compareTo(two.getText());
			}
			return one.getType().compareTo(two.getType());
		});
		
		return output;
	}
	
	public String getNextTemporarySketchName() {
		String lastName = getPref(LAST_TEMPORARY_SKETCH_NAME_PREF, "");
		
		SimpleDateFormat tempNameFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);
		String currentDateStr = tempNameFormat.format(new Date());
		
		String lastDateStr = "";
		String lastIter = "";
		
		// Temporary names are formatted "sketch_yyyyMMdda", where "a" is the iteration for
		// keeping track of multiple sketches on the same day
		
		if (lastName.length() > 0) {
			lastDateStr = lastName.substring(7, 15);
			lastIter = lastName.substring(15);
		}
		
		String currentIter = "";
		
		if (lastDateStr.equals(currentDateStr)) {
			// We need to iterate
			
			currentIter = numToBase26(base26ToNum(lastIter) + 1);
		} else {
			// This is the first temp sketch today, so start from "a" again
			currentIter = numToBase26(1);
		}
		
		return "sketch_" + currentDateStr + currentIter;
	}
	
	/**
	 * Convert numbers >= 0 into base 26 represented by letters, e.g.:<br />
	 *   0 -> a<br />
	 *   1 -> b<br />
	 *   ...<br />
	 *   25 -> z<br />
	 *   26 -> aa<br />
	 *   27 -> ab<br />
	 *   ...<br />
	 * 
	 * @param num
	 * @return
	 */
	private String numToBase26(int num) {
		if (num >= 1) {
			if (num > 26) {
				return numToBase26((num - 1) / 26) + Character.toString(numToBase26Digit((num - 1) % 26 + 1));
			} else {
				return Character.toString(numToBase26Digit(num));
			}
		} else {
			return "";
		}
	}
	
	private int base26ToNum(String base26) {
		int total = 0;
		for (int c = 0; c < base26.length(); c ++) {
			total += base26DigitToNum(base26.charAt(c)) * Math.pow(26, base26.length() - c - 1);
		}
		return total;
	}
	
	/**
	 * Convert numbers 1-26 into letters a-z
	 * 
	 * @param num
	 * @return
	 */
	private char numToBase26Digit(int num) {
		return num >= 1 && num <= 26 ? (char) (96 + num) : ' ';
	}
	
	/**
	 * Convert base 26 digits into decimal numbers
	 * 
	 * @param digit
	 * @return
	 */
	private int base26DigitToNum(char digit) {
		return digit >= 'a' && digit <= 'z' ? (int) digit - 96 : -1;
	}
	
	/**
	 * @return the location of the current sketch, be it a sketch, an example, or something else
	 */
	public MaybeDocumentFile getSketchLocation() throws MaybeDocumentFile.MaybeDocumentFileException {
		return getSketchLocation(sketchPath, sketchLocation);
	}
	
	/**
	 * @return the location of the sketch, be it a sketch, an example, or something else
	 */
	public MaybeDocumentFile getSketchLocation(String sketchPath, SketchLocation sketchLocation) throws MaybeDocumentFile.MaybeDocumentFileException {
		// Decide what to do...

		switch (sketchLocation) {
		case SKETCHBOOK:
			return getSketchbookFolder().childPathDirectory(sketchPath);
		case EXAMPLE:
			return getExamplesFolder().childPathDirectory(sketchPath);
		case LIBRARY_EXAMPLE:
			return getLibrariesFolder().childPathDirectory(sketchPath);
		case EXTERNAL:
			// TODO this might not be the best approach
			DocumentFile file = DocumentFile.fromSingleUri(this, Uri.parse(sketchPath));
			if (file != null) {
				return new MaybeDocumentFile(file);
			} else {
				throw new RuntimeException("could not load external sketch");
			}
		case TEMPORARY:
			return getTemporarySketchesFolder().childPathDirectory(sketchPath);
		default:
			throw new RuntimeException("impossible");
		}
	}
	
	public SketchLocation getSketchLocationType() {
		return sketchLocation;
	}
	
	public String getSketchPath() {
		return sketchPath;
	}
	
	/**
	 * @return a reference to the current EditorActivity
	 */
	public EditorActivity getEditor() {
		return editor;
	}
	
	public void setEditor(EditorActivity editor) {
		this.editor = editor;
	}
	
	/**
	 * @return a reference to the current SketchProperties activity
	 */
	public SketchPropertiesActivity getPropertiesActivity() {
		return propertiesActivity;
	}
	
	public void setPropertiesActivity(SketchPropertiesActivity propertiesActivity) {
		this.propertiesActivity = propertiesActivity;
	}
	
	/**
	 * @return a reference to the code area
	 */
	public CodeEditText getCodeArea() {
		return getEditor().getSelectedCodeArea();
    }
	
	/**
	 * @return the location of the Sketchbook folder on the external storage
	 */
	public MaybeDocumentFile getSketchbookFolder() throws MaybeDocumentFile.MaybeDocumentFileException {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		StorageDrive savedDrive = getSketchbookDrive();
		String externalPath = prefs.getString(SKETCHBOOK_EXTERNAL_STORAGE_PATH_PREF, DEFAULT_SKETCHBOOK_LOCATION);
		
		switch (savedDrive.type) {
			case INTERNAL:
			case SECONDARY_EXTERNAL:
			case SAF_EXTERNAL:
				return new MaybeDocumentFile(savedDrive.root);
			case EXTERNAL:
			case PRIMARY_EXTERNAL:
				return new MaybeDocumentFile(savedDrive.root).childPathDirectory(externalPath);
			default:
				return null;
		}
	}
	
	public StorageDrive getSketchbookDrive() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		ArrayList<StorageDrive> storageDrives = getStorageLocations();
		
		if (prefs.contains("internal_storage_sketchbook") && !prefs.contains(STORAGE_DRIVE_PREF)) {
			// Upgrade from the old way of doing sketchbook location (pre 0.3.3)
			
			String path;
			
			if (prefs.getBoolean("internal_storage_sketchbook", false)) {
				path = getDir("sketchbook", 0).getAbsolutePath();
			} else {
				path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getParentFile().getAbsolutePath();
			}
			
			SharedPreferences.Editor edit = prefs.edit();
			edit.putString(STORAGE_DRIVE_PREF, path);
			edit.apply();
		} else if (!prefs.contains(STORAGE_DRIVE_PREF)) {
			// Or... if there is nothing set, use the default
			
			SharedPreferences.Editor edit = prefs.edit();
			edit.putString(STORAGE_DRIVE_PREF, getDefaultSketchbookStorageDrive(storageDrives).root.getUri().toString());
			edit.apply();
		}
		
		StorageDrive savedDrive = getStorageDriveByRoot(storageDrives, prefs.getString(STORAGE_DRIVE_PREF, ""));
		String externalPath = prefs.getString(SKETCHBOOK_EXTERNAL_STORAGE_PATH_PREF, "");
		
		// Don't let the user set their sketchbook folder to be the root of their internal storage.
		// What if they want that? They really don't. I don't think it works.
		if (externalPath.equals("")) {
			SharedPreferences.Editor edit = prefs.edit();
			edit.putString(SKETCHBOOK_EXTERNAL_STORAGE_PATH_PREF, DEFAULT_SKETCHBOOK_LOCATION);
			edit.apply();
		}
		
		if (savedDrive == null || savedDrive.root == null) {
			// The drive has probably been removed
			
			System.err.println(getResources().getString(R.string.sketchbook_drive_not_found));
			
			SharedPreferences.Editor edit = prefs.edit();
			edit.putString(STORAGE_DRIVE_PREF, getDefaultSketchbookStorageDrive(storageDrives).root.getUri().toString());
			edit.apply();
			
			savedDrive = getStorageDriveByRoot(storageDrives, prefs.getString(STORAGE_DRIVE_PREF, ""));
		}
		
		assert savedDrive != null;
		
		return savedDrive;
	}
	
	public StorageDrive getSketchbookStorageDrive() {
		ArrayList<StorageDrive> storageDrives = getStorageLocations();
		
		StorageDrive storageDrive = getStorageDriveByRoot(storageDrives, PreferenceManager.getDefaultSharedPreferences(this).getString(STORAGE_DRIVE_PREF, ""));
		
		// Will be null on devices without an external storage
		return storageDrive != null ? storageDrive : getDefaultSketchbookStorageDrive(storageDrives);
	}
	
	public String getSafStorageDriveRoot() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		return prefs.getString(SKETCHBOOK_LOCATION_SAF_PREF, "");
	}
	
	public DocumentFile getSafStorageDrive() {
		String uriStr = getSafStorageDriveRoot();
		if (uriStr.length() == 0) {
			return null;
		} else {
			// TODO: error checking on this?
			return DocumentFile.fromTreeUri(this, Uri.parse(uriStr));
		}
	}
	
	public void setSafStorageDrive(Uri uri) {
		getContentResolver().takePersistableUriPermission(uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		prefs.edit()
				.putString(SKETCHBOOK_LOCATION_SAF_PREF, uri.toString())
				.putString(STORAGE_DRIVE_PREF, uri.toString())
				.apply();
	}
	
	public ArrayList<StorageDrive> getStorageLocations() {
		ArrayList<StorageDrive> locations = new ArrayList<StorageDrive>();
		
		if (Build.VERSION.SDK_INT >= SAF_API_CUTOFF) {
			locations.add(new StorageDrive(getSafStorageDrive(), StorageDrive.StorageDriveType.SAF_EXTERNAL, "n/a", getSafStorageDriveRoot()));
			locations.add(getInternalStorageDrive());
		} else {
			File primaryExtDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getParentFile();
			File[] extDirs = getExternalFilesDirs(null);
			
			for (File dir : extDirs) {
				if (dir != null) {
					if (primaryExtDir != null && dir.getAbsolutePath().startsWith(primaryExtDir.getAbsolutePath())) {
						// Use the root of the primary external storage, don't use the application-specific folder
						locations.add(new StorageDrive(DocumentFile.fromFile(primaryExtDir),
								StorageDrive.StorageDriveType.PRIMARY_EXTERNAL,
								getAvailableSpace(primaryExtDir)));
					} else {
						locations.add(new StorageDrive(DocumentFile.fromFile(new File(dir, "sketchbook")),
								StorageDrive.StorageDriveType.SECONDARY_EXTERNAL,
								getAvailableSpace(dir)));
					}
				}
			}
			
			locations.add(getInternalStorageDrive());
		}
		
		return locations;
	}
	
	public StorageDrive getInternalStorageDrive() {
		File internalDir = getDir("sketchbook", 0);
		return new StorageDrive(DocumentFile.fromFile(internalDir),
				StorageDrive.StorageDriveType.INTERNAL, getAvailableSpace(internalDir));
	}
	
	public StorageDrive getDefaultSketchbookStorageDrive(ArrayList<StorageDrive> storageDrives) {
		StorageDrive firstChoice = null;
		StorageDrive secondChoice = null;
		
		for (StorageDrive storageDrive : storageDrives) {
			if (storageDrive.type.equals(StorageDrive.StorageDriveType.PRIMARY_EXTERNAL)
					|| storageDrive.type.equals(StorageDrive.StorageDriveType.EXTERNAL)) {
				
				firstChoice = storageDrive;
			} else if (storageDrive.type.equals(StorageDrive.StorageDriveType.INTERNAL)) {
				secondChoice = storageDrive;
			}
		}
		
		return firstChoice != null ? firstChoice : secondChoice;
	}
	
	/**
	 * Change the storage drive preference to use the internal storage.
	 *
	 * Note that this function will not move any files from the sketchbook to the new storage
	 * location, it just updates the preference.
	 */
	public void useInternalStorageDrive() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		SharedPreferences.Editor edit = prefs.edit();
		edit.putString(STORAGE_DRIVE_PREF, getInternalStorageDrive().root.toString());
		edit.apply();
	}
	
	public StorageDrive getStorageDriveByRoot(ArrayList<StorageDrive> storageDrives, String root) {
		for (StorageDrive storageDrive : storageDrives) {
			if (storageDrive.rootString != null && storageDrive.rootString.equals(root)) {
				return storageDrive;
			}
		}
		
		return null;
	}
	
	public static class StorageDrive {
		public DocumentFile root;
		public StorageDriveType type;
		public String space;
		public String rootString;
		
		public StorageDrive(DocumentFile root, StorageDriveType type, String space, String rootString) {
			this.root = root;
			this.type = type;
			this.space = space;
			if (rootString == null && root != null) {
				this.rootString = root.getUri().toString();
			} else {
				this.rootString = rootString;
			}
		}
		
		public StorageDrive(DocumentFile root, StorageDriveType type, String space) {
			this(root, type, space, null);
		}
		
		public enum StorageDriveType {
			INTERNAL ("Internal"),
			EXTERNAL ("External"),
			PRIMARY_EXTERNAL ("Primary External"),
			SECONDARY_EXTERNAL ("Secondary External"),
			SAF_EXTERNAL ("External");
			
			public String title;
			
			StorageDriveType(String title) {
				this.title = title;
			}
			
			public boolean requiresExternalStoragePermission() {
				switch (this) {
					case EXTERNAL:
					case PRIMARY_EXTERNAL:
						return true;
					case INTERNAL:
					case SECONDARY_EXTERNAL:
					case SAF_EXTERNAL:
						return false;
					default:
						throw new RuntimeException("should be impossible");
				}
			}
		}
		
		public String getUriString() {
			if (rootString != null) {
				return rootString;
			} else {
				return "n/a";
			}
		}
	}
	
	final static double BYTE_PER_GB = 1_073_741_824.0d; // 1024^3
	
	public static String getAvailableSpace(File drive) {
		try {
			StatFs stat = new StatFs(drive.getAbsolutePath());
			
			DecimalFormat df = new DecimalFormat("#.00");
			df.setRoundingMode(RoundingMode.HALF_UP);
			
			long available;
			long total;
			
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
				available = stat.getAvailableBytes();
				total = stat.getTotalBytes();
			} else {
				available = stat.getAvailableBlocks() * stat.getBlockSize();
				total = stat.getBlockCount() * stat.getBlockSize();
			}
			
			return df.format(available / BYTE_PER_GB) + " GB free of " + df.format(total / BYTE_PER_GB) + " GB";
		} catch (IllegalArgumentException e) {
			return "Failed to stat FS";
		}
	}
	
	/**
	 * @return the location of the libraries folder within the Sketchbook
	 */
	public MaybeDocumentFile getLibrariesFolder() throws MaybeDocumentFile.MaybeDocumentFileException {
		return getSketchbookFolder().childDirectory(LIBRARIES_FOLDER);
	}
	
	public File getStarterExamplesFolder() {
		return getDir("examples", 0);
	}
	
	public File getExamplesRepoFolder() {
		return getDir("examples_repo", 0);
	}
	
	public MaybeDocumentFile getTemporarySketchesFolder() {
		try {
			return MaybeDocumentFile.fromDirectory(getDir("temporary", 0), this);
		} catch (MaybeDocumentFile.MaybeDocumentFileException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * @return the location of the examples folder on the private internal storage
	 */
	public MaybeDocumentFile getExamplesFolder() {
		/*
		 * We're using the internal private storage directory for now
		 * 
		 * Benefits:
		 *  - Available on all devices
		 *  - Users can't mess with it
		 *  
		 * Downsides:
		 *  - Adds to the app's apparent required storage
		 *  - Will fail if the internal storage is full
		 */
		
		File examplesRepo = getExamplesRepoFolder();
		
		try {
			if (examplesRepo != null && examplesRepo.list().length > 0) {
				// Let's use the examples repo if it's been initialized
				return MaybeDocumentFile.fromDirectory(examplesRepo, this);
			} else {
				return MaybeDocumentFile.fromDirectory(getStarterExamplesFolder(), this);
			}
		} catch (MaybeDocumentFile.MaybeDocumentFileException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Disable SSL3 to force TLS. Fix for Android 4.4 and below.
	 *
	 * https://stackoverflow.com/questions/29916962/javax-net-ssl-sslhandshakeexception-javax-net-ssl-sslprotocolexception-ssl-han
	 */
	public void disableSsl3() {
		try {
			ProviderInstaller.installIfNeeded(this);
		} catch (GooglePlayServicesNotAvailableException | GooglePlayServicesRepairableException e) {
			// Too bad
			System.err.println("Failed to disable SSL3");
		}
	}
	
	public void initExamplesRepo() {
		// Don't bother checking if the user doesn't want to
		if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean("update_examples", true)) {
			return;
		}
		
		// Make sure to force TLS
		disableSsl3();
		
		// Initialize the examples repository when the app is first opened
		
		final ConnectivityManager connection = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		final NetworkInfo wifi = connection.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		final NetworkInfo mobile = connection.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
		
		// Make sure we're on Wi-Fi
		if ((wifi != null && wifi.isConnected())
				|| (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("update_examples_mobile_data", false)
				&& mobile != null && mobile.isConnected())) {
			// We have to do this on a non-UI thread...
			
			new Thread(new Runnable() {
				public void run() {
					// For some reason, there's a lot of useless console output here...
					//
					// This will also block console output for a couple of
					// things that run after this because this runs in a
					// separate thread, but it's worth it. The user can turn
					// the output back on from Settings if it becomes a problem.
					getEditor().FLAG_SUSPEND_OUT_STREAM.set(true);
					
					try {
						GitRepository examplesRepo = new GitRepository(getExamplesRepoFolder());
						
						// Check to see if an update is available
						if (!examplesRepo.exists() || (examplesRepo.exists() && examplesRepo.canPull(EXAMPLES_REPO, EXAMPLES_REPO_BRANCH))) {
							examplesRepo.close();
							
							// Yucky multi-threading
							editor.runOnUiThread(() -> {
								AlertDialog.Builder builder = new AlertDialog.Builder(editor);
								
								builder.setTitle(R.string.examples_update_dialog_title);
								
								LinearLayout layout = (LinearLayout) View.inflate(new ContextThemeWrapper(editor, R.style.Theme_AppCompat_Dialog), R.layout.examples_update_dialog, null);
								
								final CheckBox dontShowAgain = (CheckBox) layout.findViewById(R.id.examples_update_dialog_dont_show_again);
								final TextView disableWarning = (TextView) layout.findViewById(R.id.examples_update_dialog_disable_warning);
								
								builder.setView(layout);
								
								builder.setPositiveButton(R.string.examples_update_dialog_update_button, new DialogInterface.OnClickListener() {
									@Override
									public void onClick(DialogInterface dialog, int which) {
										if (dontShowAgain.isChecked()) {
											// "Close"
											
											// Disable examples updates
											SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(APDE.this).edit();
											edit.putBoolean("update_examples", false);
											edit.apply();
										} else {
											// "Update"
											
											// This kicks off yet another thread
											updateExamplesRepo();
										}
									}
								});

								builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
									@Override
									public void onClick(DialogInterface dialog, int which) {
									}
								});
								
								AlertDialog dialog = builder.create();
								dialog.show();
								
								final Button updateButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
								final Button cancelButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
								
								dontShowAgain.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
									@Override
									public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
										disableWarning.setVisibility(isChecked ? View.VISIBLE : View.GONE);
										// Change the behavior if the user wants to get rid of this dialog...
										updateButton.setText(isChecked ? R.string.tool_find_replace_close : R.string.examples_update_dialog_update_button);
										// Hide the cancel button so that it's unambiguous
										cancelButton.setEnabled(!isChecked);
									}
								});
							});
						}
					} finally {
						// Make sure that we turn console output back on!!!
						getEditor().FLAG_SUSPEND_OUT_STREAM.set(false);
					}
				}
			}).start();
		}
	}
	
	private void updateExamplesRepo() {
		editor.message(getResources().getString(R.string.examples_update_progress_message));
		
		//We have to do this on a non-UI thread...
		
		new Thread(new Runnable() {
			public void run() {
				getEditor().FLAG_SUSPEND_OUT_STREAM.set(true);
				
				GitRepository examplesRepo = new GitRepository(getExamplesRepoFolder());
				
				boolean update = false;
				
				if (examplesRepo.exists()) {
					if (examplesRepo.canPull(EXAMPLES_REPO, EXAMPLES_REPO_BRANCH)) {
						examplesRepo.pullRepo(EXAMPLES_REPO, EXAMPLES_REPO_BRANCH);
						update = true;
					}
					
					//Make sure to close
					examplesRepo.close();
				} else {
					//We need to close before so that we can write to the directory
					examplesRepo.close();
					
					GitRepository.cloneRepo(EXAMPLES_REPO, examplesRepo.getRootDir(), EXAMPLES_REPO_BRANCH);
					update = true;
				}
				
				if (update) {
					editor.runOnUiThread(new Runnable() {
						public void run() {
							editor.forceDrawerReload();
							editor.message(getResources().getString(R.string.examples_update_success));
						}
					});
				}
				
				getEditor().FLAG_SUSPEND_OUT_STREAM.set(false);
			}
		}).start();
	}
	
	public void redownloadExamplesNow(final Activity activityContext) {
		//Don't bother checking if the user doesn't want to
		if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean("update_examples", true)) {
			return;
		}
		
		//Initialize the examples repository when the app is first opened
		
		final ConnectivityManager connection = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		final NetworkInfo wifi = connection.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		final NetworkInfo mobile = connection.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
		
		//Make sure we're on Wi-Fi
		if ((wifi != null && wifi.isConnected())
				|| (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("update_examples_mobile_data", false)
				&& mobile != null && mobile.isConnected())) {
			//We have to do this on a non-UI thread...
			
			new Thread(new Runnable() {
				public void run() {
					GitRepository examplesRepo = new GitRepository(getExamplesRepoFolder());
					
					//Delete the repository
					try {
						deleteFile(examplesRepo.getRootDir());
					} catch (IOException e) {
						e.printStackTrace();
						return;
					}
					examplesRepo.close();
					
					activityContext.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							//Now re-update it
							updateExamplesRepo();
						}
					});
				}
			}).start();
		} else {
			AlertDialog.Builder builder = new AlertDialog.Builder(activityContext);
			builder.setTitle(R.string.examples_update_settings_download_now_mobile_data_error_dialog_title);
			builder.setMessage(R.string.examples_update_settings_download_now_mobile_data_error_dialog_message);
			
			builder.setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {}
			});
			
			builder.create().show();
		}
	}
	
	public void moveFolder(SketchMeta source, SketchMeta dest, Activity activityContext) throws MaybeDocumentFile.MaybeDocumentFileException, FileNotFoundException {
		final MaybeDocumentFile sourceFile = getSketchLocation(source.getPath(), source.getLocation());
		final MaybeDocumentFile destFile = getSketchLocation(dest.getPath(), dest.getLocation());
		
		final boolean isSketch = validSketch(sourceFile);
		final boolean selected = getSketchLocation().equals(getSketchLocation(source.getPath(), source.getLocation()));
		
		if (!sourceFile.exists()) {
			throw new FileNotFoundException("Source file does not exist: " + sourceFile.toString());
		}
		
		// Let's not overwrite anything...
		// TODO Maybe give the user options to replace / keep both in the new location?
		// We don't need that much right now, they can deal with things manually...
		if (destFile.exists()) {
			AlertDialog.Builder builder = new AlertDialog.Builder(activityContext);
			
			builder.setTitle(isSketch ? R.string.rename_sketch_failure_title : R.string.rename_move_folder_failure_title);
			builder.setMessage(isSketch ? R.string.rename_sketch_failure_message : R.string.rename_move_folder_failure_message);
			
			builder.setNeutralButton(R.string.ok, (dialog, which) -> {});
			
			builder.create().show();
			
			return;
		}
		
		// TODO Maybe run in a separate thread if the file size is large enough?
		
		if (performMoveSketch(sourceFile.resolve(), destFile) && selected) {
			selectSketch(dest.getPath(), dest.getLocation());
			putRecentSketch(dest.getLocation(), dest.getPath());
		}
		
		editor.forceDrawerReload();
	}
	
	private boolean performMoveSketch(DocumentFile sourceFile, MaybeDocumentFile destFile) {
		try {
			copyDocumentFile(sourceFile, destFile, getContentResolver());
		} catch (IOException | MaybeDocumentFile.MaybeDocumentFileException e) {
			System.err.println(getResources().getString(R.string.move_folder_error_moving_sketch));
			e.printStackTrace();
			
			return false;
		}
		
		try {
			deleteDocumentFile(sourceFile);
		} catch (IOException e) {
			System.err.println(getResources().getString(R.string.move_folder_error_deleting_old_sketch));
			e.printStackTrace();
			
			return false;
		}
		
		return true;
	}
	
	public static void copyDocumentFile(DocumentFile sourceFile, MaybeDocumentFile destFile,
	                                    ContentResolver contentResolver) throws MaybeDocumentFile.MaybeDocumentFileException, IOException {
		if (sourceFile.isDirectory()) {
			for (DocumentFile content : sourceFile.listFiles()) {
				copyDocumentFile(content, destFile.child(content.getName(), content.getType()), contentResolver);
			}
		} else {
			if (!destFile.exists()) {
				destFile.resolve();
			}
			
			try (InputStream source = contentResolver.openInputStream(sourceFile.getUri());
			     OutputStream destination = destFile.openOut(contentResolver)) {
				byte[] buffer = new byte[4096];
				int read;
				while ((read = source.read(buffer)) > 0) {
					destination.write(buffer, 0, read);
				}
			}
		}
	}
	
	public static void copyFile(File sourceFile, File destFile) throws IOException {
		if (sourceFile.isDirectory()) {
    		for (File content : sourceFile.listFiles()) {
    			copyFile(content, new File(destFile, content.getName()));
    		}
    		
    		// Don't try to copy the folder using file methods, it won't work
    		return;
    	}
		
		if (!destFile.exists()) {
			destFile.getParentFile().mkdirs();
			destFile.createNewFile();
		}
		
		try (FileChannel source = new FileInputStream(sourceFile).getChannel();
		     FileChannel destination = new FileOutputStream(destFile).getChannel()) {
			destination.transferFrom(source, 0, source.size());
		}
	}
	
	public static void deleteDocumentFile(DocumentFile file, boolean suppressFailure) throws IOException {
		if (file.exists()) {
			if (file.isDirectory()) {
				for (DocumentFile content : file.listFiles()) {
					deleteDocumentFile(content, suppressFailure);
				}
			}
			
			if (!file.delete()) {
				if (suppressFailure) {
					System.err.println("Failed to delete file: " + file);
				} else {
					throw new FileNotFoundException("Failed to delete file: " + file);
				}
			}
		}
	}
	
	public static void deleteDocumentFile(DocumentFile file) throws IOException {
		deleteDocumentFile(file, false);
	}
	
	/**
	 * Recursive file deletion
	 * 
	 * @param file
	 * @throws IOException
	 */
    public static void deleteFile(File file, boolean suppressFailure) throws IOException {
    	if (file.exists()) {
			if (file.isDirectory()) {
				for (File content : file.listFiles()) {
					deleteFile(content);
				}
			}
		
			if (!file.delete()) { //Uh-oh...
				if (suppressFailure) {
					System.err.println("Failed to delete file: " + file);
				} else {
					throw new FileNotFoundException("Failed to delete file: " + file);
				}
			}
		}
    }
    
    public static void deleteFile(File file) throws IOException {
    	deleteFile(file, false);
	}
	
	/**
	 * @return the current version code of APDE
	 */
	public int appVersionCode() {
		try {
			// http://stackoverflow.com/questions/6593592/get-application-version-programatically-in-android
			PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
			return pInfo.versionCode;
		} catch (NameNotFoundException e) {
			e.printStackTrace();
		}

		return -1;
	}
	
	public static String readAssetFile(Context context, String filename) {
		try {
			InputStream assetStream = context.getAssets().open(filename);
			BufferedInputStream stream = new BufferedInputStream(assetStream);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			
			byte[] buffer = new byte[1024];
			int numRead;
			while ((numRead = stream.read(buffer)) != -1) {
				out.write(buffer, 0, numRead);
			}
			
			stream.close();
			assetStream.close();
			
			String text = new String(out.toByteArray());
			
			out.close();
			
			return text;
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return "";
	}
	
	/**
	 * @return whether or not the current sketch is an example
	 */
	public boolean isExample() {
		return sketchLocation.equals(SketchLocation.EXAMPLE) || sketchLocation.equals(SketchLocation.LIBRARY_EXAMPLE);
	}
	
	/**
	 * @return whether or not the sketch is temporary
	 */
	public boolean isTemp() {
		return sketchLocation.equals(SketchLocation.TEMPORARY);
	}
	
	/**
	 * @return whether or not the sketch is in the sketchbook
	 */
	public boolean isSketchbook() {
		return sketchLocation.equals(SketchLocation.SKETCHBOOK);
	}
	
	public void putRecentSketch(SketchLocation location, String path) throws MaybeDocumentFile.MaybeDocumentFileException {
		ArrayList<SketchMeta> oldSketches = getRecentSketches();
		SketchMeta[] sketches = new SketchMeta[oldSketches.size() + 1];
		
		//Add the new sketch
		sketches[0] = new SketchMeta(location, path);
		//Copy all of the old sketches over
		System.arraycopy(oldSketches.toArray(), 0, sketches, 1, oldSketches.size());
		
		//We should get a list with the newest sketches on top...
		
		String data = "";
		
		for (int i = 0; i < sketches.length; i ++) {
			data += sketches[i].getLocation().toString() + "," + sketches[i].getPath() + ",\n";
		}
		
		PreferenceManager.getDefaultSharedPreferences(this).edit().putString("recent", data).commit();
	}
	
	public ArrayList<SketchMeta> getRecentSketches() throws MaybeDocumentFile.MaybeDocumentFileException {
		String data = PreferenceManager.getDefaultSharedPreferences(this).getString("recent", "");
		String[] sketchLines = data.split("\n");
		
		ArrayList<SketchMeta> sketches = new ArrayList<SketchMeta>(sketchLines.length);
		
		//20 here is the number of sketches to keep in the recent list
		//TODO maybe make this a preference?
		for(int i = Math.min(sketchLines.length - 1, 20); i >= 0; i --) {
			String[] parts = sketchLines[i].split(",");
			
			//Skip over bad data - this should only happen if the saved data is empty
			if(parts.length < 2) {
				continue;
			}
			
			SketchMeta sketch = new SketchMeta(SketchLocation.fromString(parts[0]), parts[1]);
			
			//Filter out bad sketches
			if(!validSketch(getSketchLocation(sketch.getPath(), sketch.getLocation()))) {
				continue;
			}
			
			//Avoid duplicates
			for(int j = 0; j < sketches.size(); j ++) {
				if(sketches.get(j).equals(sketch)) {
					sketches.remove(j);
				}
			}
			
			sketches.add(sketch);
		}
		
		//Reverse the list...
		Collections.reverse(sketches);
		
		return sketches;
	}
	
	public ArrayList<FileNavigatorAdapter.FileItem> listRecentSketches() throws MaybeDocumentFile.MaybeDocumentFileException {
		ArrayList<SketchMeta> sketches = getRecentSketches();
		
		ArrayList<FileNavigatorAdapter.FileItem> fileItems = new ArrayList<FileNavigatorAdapter.FileItem>(sketches.size() + 1);
		
		//Add the "navigate up" button
		fileItems.add(new FileNavigatorAdapter.FileItem(FileNavigatorAdapter.NAVIGATE_UP_TEXT, FileNavigatorAdapter.FileItemType.NAVIGATE_UP));
		
		for(int i = 0; i < sketches.size(); i ++) {
			fileItems.add(new FileNavigatorAdapter.FileItem(sketches.get(i).getLocation().toReadableString(this) + sketches.get(i).getPathPrefix(), sketches.get(i).getName(), FileNavigatorAdapter.FileItemType.SKETCH,
					false, false, sketches.get(i)));
		}
		
		if(sketches.size() == 0) {
			//Let the user know that the folder is empty...
			fileItems.add(new FileNavigatorAdapter.FileItem(getResources().getString(R.string.drawer_folder_empty), FileNavigatorAdapter.FileItemType.MESSAGE));
		}
		
		return fileItems;
	}
	
	public static class SketchMeta {
		private SketchLocation location;
		private String path;
		
		public SketchMeta(SketchLocation location, String path) {
			this.location = location;
			this.path = path;
		}
		
		public SketchLocation getLocation() {
			return location;
		}
		
		public String getPath() {
			return path;
		}
		
		public String getPathPrefix() {
			int lastSlash = path.lastIndexOf('/');
			
			return lastSlash != -1 ? path.substring(0, lastSlash + 1) : "";
		}
		
		public String getName() {
			int lastSlash = path.lastIndexOf('/');
			
			return lastSlash != -1 && path.length() > lastSlash + 1 ? path.substring(lastSlash + 1, path.length()) : path;
		}
		
		public String getParent() {
			int lastSlash = path.lastIndexOf('/');
			
			return lastSlash != -1 ? path.substring(0, lastSlash) : "";
		}
		
		@Override
		public boolean equals(Object other) {
			if(other instanceof SketchMeta) {
				SketchMeta otherSketchMeta = (SketchMeta) other;
				
				return otherSketchMeta.getLocation().equals(location) && otherSketchMeta.getPath().equals(path);
			} else {
				return false;
			}
		}
		
		@Override
		public String toString() {
			return location.toString() + "," + path;
		}
		
		public static SketchMeta fromString(String value) {
			String[] parts = value.split(",");
			
			if(parts.length != 2) {
				return null;
			}
			
			return new SketchMeta(SketchLocation.fromString(parts[0]), parts[1]);
		}
	}
	
	/**
	 * @return the sketch properties of the current sketch
	 */
	public SketchProperties getProperties() throws MaybeDocumentFile.MaybeDocumentFileException {
		return getProperties(BuildContext.create(this));
	}
	
	public SketchProperties getProperties(BuildContext buildContext) throws MaybeDocumentFile.MaybeDocumentFileException {
		// TODO maybe load once and store reference?
		if (needsPropertiesUpgrade()) {
			// Sketch still has an AndroidManifest.xml, upgrade to sketch.properties
			return upgradeManifestToProperties(buildContext);
		} else {
			// Load sketch.properties
			MaybeDocumentFile propertiesFile = getSketchPropertiesFile();
			SketchProperties properties = new SketchProperties(buildContext, propertiesFile);
			// Create properties if they don't exist
			if (!propertiesFile.exists() && !isExample()) {
				properties.save(getSketchPropertiesFile());
			}
			return properties;
		}
	}
	
	public String getSketchPackageName() throws MaybeDocumentFile.MaybeDocumentFileException {
		return getProperties().getPackageName(getSketchName());
	}
	
	public MaybeDocumentFile getSketchPropertiesFile() throws MaybeDocumentFile.MaybeDocumentFileException {
		return getSketchLocation().child("sketch.properties", "text/x-java-properties");
	}
	
	protected boolean needsPropertiesUpgrade() {
		// Determine whether or not we need to upgrade to sketch.properties
		
		try {
			// We can't upgrade if we don't have a manifest
			MaybeDocumentFile manifestFile = getSketchLocation().child(Manifest.MANIFEST_XML, "application/xml");
			if (manifestFile == null || !manifestFile.exists()) {
				return false;
			}
			
			// First, see if file exists - if not we need to upgrade
			if (getSketchPropertiesFile().exists()) {
				try {
					// Load the properties file
					Properties properties = new Properties();
					SketchProperties.loadProperties(properties,
							getSketchPropertiesFile().openIn(getContentResolver()));
					// If it has our keys then it should be good to go, otherwise we need to upgrade
					return !properties.containsKey(SketchProperties.KEY_PACKAGE_NAME);
				} catch (IOException e) {
					e.printStackTrace();
					return false;
				}
			} else {
				return true;
			}
		} catch (MaybeDocumentFile.MaybeDocumentFileException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	protected SketchProperties upgradeManifestToProperties(BuildContext buildContext) throws MaybeDocumentFile.MaybeDocumentFileException {
		// The old AndroidManifest.xml
		MaybeDocumentFile manifestFile = getSketchLocation().child(Manifest.MANIFEST_XML, "application/xml");
		Manifest manifest = new Manifest(buildContext);
		manifest.load(manifestFile, ComponentTarget.APP);
		
		// Upgrade to Android mode 3.0 (activity -> fragment) if needed
		if (manifest.needsProcessing3Update()) {
			manifest.updateProcessing3();
		}
		
		// Make sketch properties from the manifest
		SketchProperties properties = manifest.copyToProperties();
		
		// Don't change files of examples
		if (!editor.getGlobalState().isExample()) {
			// Make sketch.properties
			properties.save(editor.getGlobalState().getSketchPropertiesFile());
			
			// Note: the manifest is still there, so it will still work on the desktop
		}
		
		return properties;
	}
	
	private File getBuildFolder(boolean alternate) {
		// Let the user pick where to build
		if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean("pref_build_internal_storage", true) ^ alternate) {
			return getDir("build", 0);
		} else {
			return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getParentFile(), "build");
		}
	}
	
	public File getBuildFolder() {
		return getBuildFolder(false);
	}
	
	/**
	 * @return the other build folder not currently in use, i.e. /sdcard/build if using the internal
	 * storage to build
	 */
	public File getAlternateBuildFolder() {
		return getBuildFolder(true);
	}
	
	public void rebuildLibraryList() throws MaybeDocumentFile.MaybeDocumentFileException {
		importToLibraryTable.clear();
		
		MaybeDocumentFile contribLibrariesFolder = getLibrariesFolder();
		if (contribLibrariesFolder != null && contribLibrariesFolder.exists()) {
			contributedLibraries = Library.list(contribLibrariesFolder.resolve(), this);
			for (Library lib : contributedLibraries) {
				lib.addPackageList(importToLibraryTable,
						(APDE) editor.getApplicationContext());
			}
		} else {
			contributedLibraries = Collections.emptyList();
		}
	}
	
	public Map<String, List<Library>> getImportToLibraryTable() {
		return importToLibraryTable;
	}
	
	public List<Library> getLibraries() {
		return contributedLibraries;
	}
	
	public String[] listLibraries() {
		String[] output = new String[contributedLibraries.size()];
		for (int i = 0; i < contributedLibraries.size(); i++) {
			output[i] = contributedLibraries.get(i).getName();
		}
		
		return output;
	}
	
	/**
	 * @param name
	 * @return the library with the specified name, or null if it cannot be
	 *         found
	 */
	public Library getLibraryByName(String name) {
		for (Library lib : getLibraries()) {
			if (lib.getName().equals(name)) {
				return lib;
			}
		}

		return null;
	}
	
	public void rebuildToolList() {
		if (tools == null) {
			tools = new ArrayList<Tool>();
		} else {
			tools.clear();
		}
		
		if (packageToToolTable == null) {
			packageToToolTable = new HashMap<String, Tool>();
		} else {
			packageToToolTable.clear();
		}
		
		String[] coreTools = new String[] {
				AutoFormat.PACKAGE_NAME, ImportLibrary.PACKAGE_NAME,
				ManageLibraries.PACKAGE_NAME, ColorSelector.PACKAGE_NAME,
				CommentUncomment.PACKAGE_NAME, IncreaseIndent.PACKAGE_NAME, DecreaseIndent.PACKAGE_NAME,
				/*ExportGradleProject.PACKAGE_NAME,*/ ExportSignedPackage.PACKAGE_NAME,
				FindInReference.PACKAGE_NAME, OpenReference.PACKAGE_NAME,
				GitManager.PACKAGE_NAME, FindReplace.PACKAGE_NAME,
				UninstallSketch.PACKAGE_NAME, ManageWallpapers.PACKAGE_NAME
		};
		
		for (String coreTool : coreTools) {
			loadTool(tools, packageToToolTable, coreTool);
		}
		
		// Sort the tools alphabetically
		Collections.sort(tools, new Comparator<Tool>() {
			@Override
			public int compare(Tool a, Tool b) {
				return a.getMenuTitle().compareTo(b.getMenuTitle());
			}
		});
	}
	
	private void loadTool(List<Tool> list, Map<String, Tool> table, String toolName) {
		try {
			Class<?> toolClass = Class.forName(toolName);
			Tool tool = (Tool) toolClass.newInstance();
			
			tool.init(this);
			
			list.add(tool);
			table.put(toolName, tool);
		} catch (Error | Exception e) {
			System.err.println(String.format(Locale.US, getResources().getString(R.string.tool_load_failed), toolName));
			e.printStackTrace();
		}
	}
	
	public Map<String, Tool> getPackageToToolTable() {
		return packageToToolTable;
	}
	
	public List<Tool> getTools() {
		return tools;
	}
	
	public ArrayList<Tool> getToolsInList() {
		ArrayList<Tool> inList = new ArrayList<Tool>();
		
		for(Tool tool : tools) {
			if(tool.showInToolsMenu(getSketchLocationType())) {
				inList.add(tool);
			}
		}
		
		return inList;
	}
	
	public String[] listToolsInList() {
		ArrayList<Tool> inList = getToolsInList();
		
		String[] output = new String[inList.size()];
		for (int i = 0; i < inList.size(); i++) {
			output[i] = inList.get(i).getMenuTitle();
		}

		return output;
	}
	
	public Tool getToolByPackageName(String packageName) {
		return packageToToolTable.get(packageName);
	}
	
	private boolean processingPrefsInitialized = false;
	
	public void initProcessingPrefs() {
		if (processingPrefsInitialized) {
			// Don't do this again
			return;
		}
		
		//Make pde.jar behave properly
		
		//Set up the Processing-specific folder
		final File dir = getDir("processing_home", 0);
		
		if (!dir.exists()) {
			dir.mkdir();
			
			//Put the default preferences file where Processing will look for it
			EditorActivity.copyAssetFolder(getAssets(), "processing-default", dir.getAbsolutePath());
		}
		
		//Some magic to put our own platform in place
		AndroidPlatform.setDir(dir);
		
		try {
			Field inst = Platform.class.getDeclaredField("inst");
			inst.setAccessible(true);
			inst.set(null, new AndroidPlatform());
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		processingPrefsInitialized = true;
	}
	
	public boolean getPref(String pref, boolean def) {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(pref, def);
	}
	
	public String getPref(String pref, String def) {
		return PreferenceManager.getDefaultSharedPreferences(this).getString(pref, def);
	}
	
	public void putPref(String pref, boolean val) {
		SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(this).edit();
		edit.putBoolean(pref, val);
		edit.apply();
	}
	
	public void putPref(String pref, String val) {
		SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(this).edit();
		edit.putString(pref, val);
		edit.apply();
	}
	
	public EditText createAlertDialogEditText(Activity context, AlertDialog.Builder builder, String content, boolean selectAll) {
		final EditText input = new EditText(context);
		input.setSingleLine();
		input.setText(content);
		if (selectAll) {
			input.selectAll();
		}
		
		// http://stackoverflow.com/a/27776276/
		FrameLayout frameLayout = new FrameLayout(context);
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
		
		// http://stackoverflow.com/a/35211225/
		float dpi = getResources().getDisplayMetrics().density;
		params.leftMargin = (int) (19 * dpi);
		params.topMargin = (int) (5 * dpi);
		params.rightMargin = (int) (14 * dpi);
		params.bottomMargin = (int) (5 * dpi);
		
		frameLayout.addView(input, params);
		
		builder.setView(frameLayout);
		
		return input;
	}
	
	public void assignLongPressDescription(final ImageButton button, final int descId) {
		button.setOnLongClickListener(v -> {
			Toast toast = Toast.makeText(getEditor(), descId, Toast.LENGTH_SHORT);
			positionToast(toast, button, getEditor().getWindow(), 0, 0);
			toast.show();
			
			return true;
		});
	}
	
	/** Mimic native Android action bar button long-press behavior.
	 * http://stackoverflow.com/a/21026866
	 */
	public static void positionToast(Toast toast, View view, Window window, int offsetX, int offsetY) {
		// toasts are positioned relatively to decor view, views relatively to their parents, we have to gather additional data to have a common coordinate system
		Rect rect = new Rect();
		window.getDecorView().getWindowVisibleDisplayFrame(rect);
		// covert anchor view absolute position to a position which is relative to decor view
		int[] viewLocation = new int[2];
		view.getLocationInWindow(viewLocation);
		int viewLeft = viewLocation[0] - rect.left;
		int viewTop = viewLocation[1] - rect.top;
		
		// measure toast to center it relatively to the anchor view
		int toastWidth;
		if (toast.getView() == null) {
			// TODO: view is null on Android 11+. find a better way to do this.
			toastWidth = 0;
		} else {
			DisplayMetrics metrics = new DisplayMetrics();
			window.getWindowManager().getDefaultDisplay().getMetrics(metrics);
			int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.UNSPECIFIED);
			int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.UNSPECIFIED);
			toast.getView().measure(widthMeasureSpec, heightMeasureSpec);
			toastWidth = toast.getView().getMeasuredWidth();
		}
		
		// compute toast offsets
		int toastX = viewLeft + (view.getWidth() / 2 - toastWidth) + offsetX;
		int toastY = viewTop + view.getHeight() + offsetY;
		
		toast.setGravity(Gravity.LEFT | Gravity.TOP, toastX, toastY);
	}
	
	public void launchSketchFolder(Activity activityContext) {
		if (isExample() || isTemp() || (isSketchbook()
				&& getSketchbookDrive().type.equals(StorageDrive.StorageDriveType.INTERNAL))) {
			
			// Don't try opening sketches that are in places that we can't see
			return;
		}
		
		// This is very broken. Most file manager seem unable to display a folder.
		// The only file manager that works is Open Intents (OI), which uses a separate mechanism.
		// TODO Need to create an in-app file browser to avoid depending on an external file browser
		
		// TODO: unclear how to do this with DocumentFiles
		
//		DocumentFile sketchFolder = getSketchLocation();
//		Intent intent = new Intent(Intent.ACTION_VIEW);
//		Uri uri;
//
//		if (android.os.Build.VERSION.SDK_INT >= 30) {
//			// TODO don't know if we can do this
//			uri = null;
//		} else if (android.os.Build.VERSION.SDK_INT >= 24) {
//			// Need to use FileProvider
//			uri = FileProvider.getUriForFile(activityContext, "com.calsignlabs.apde.fileprovider", sketchFolder);
//			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
//		} else {
//			uri = Uri.fromFile(sketchFolder);
//		}
//
//		// Support OI File Manager - perhaps the only file manager that supports displaying folders
//		intent.putExtra("org.openintents.extra.ABSOLUTE_PATH", sketchFolder.getAbsolutePath());
//		intent.setDataAndType(uri, "*/*");
//		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // Start this in a separate task
//		activityContext.startActivity(Intent.createChooser(intent, getResources().getString(R.string.show_sketch_folder_title)));
	}
	
	public ModularBuild getModularBuild() {
		if (modularBuild == null) {
			modularBuild = new ModularBuild(this);
		}
		
		return modularBuild;
	}
	
	public static final SimpleDateFormat DEBUG_LOG_TIMESTAMP_FORMATTER = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
	
	public void writeDebugLog(String tag, String msg) {
		try {
			MaybeDocumentFile output = getSketchbookFolder().child("debugLog.txt", "text/plain");
			OutputStream out = output.openOut(getContentResolver());
			Writer writer = new BufferedWriter(new OutputStreamWriter(out));
			
			writer.write(DEBUG_LOG_TIMESTAMP_FORMATTER.format(new Date()) + "    " + tag + "    " + msg + "\n");
			
			writer.flush();
			writer.close();
			out.flush();
			out.close();
		} catch (IOException | MaybeDocumentFile.MaybeDocumentFileException e) {
			e.printStackTrace();
		}
	}
	
	public void writeCodeDeletionDebugStatus(String loc) {
		if (getPref("pref_debug_code_deletion_debug_logs", false)) {
			if (editor == null) {
				// Sometimes this can happen
				writeDebugLog(loc, "Editor is null");
				return;
			}
			
			StringBuilder msg = new StringBuilder();
			
			msg.append("\n");
			
			for (SketchFile sketchFile : editor.getSketchFiles()) {
				try {
					MaybeDocumentFile file = getSketchLocation().child(sketchFile.getFilename(), "text/pde");
					if (file != null && file.exists()) {
						msg.append("    FS - ");
						msg.append(sketchFile.getFilename());
						msg.append(": ");
						msg.append(file.resolve().length());
						msg.append("\n");
					} else {
						msg.append("   FS - null\n");
					}
				} catch (MaybeDocumentFile.MaybeDocumentFileException e) {
					msg.append("    FS - failed to read\n");
				}
			}
			
			for (SketchFile sketchFile : editor.getSketchFiles()) {
				msg.append("    SketchFile - ");
				msg.append(sketchFile.getFilename());
				msg.append(": ");
				msg.append(sketchFile.getText().length());
				msg.append("\n");
			}
			
			CodeEditText codeEditText = editor.getSelectedCodeArea();
			if (codeEditText != null) {
				msg.append("    CodeEditText - ");
				msg.append(editor.getSketchFiles().get(editor.getSelectedCodeIndex()).getFilename());
				msg.append(": ");
				msg.append(editor.getSelectedCodeArea().getText().length());
				msg.append("\n");
			} else {
				msg.append("    CodeEditText null\n");
			}
			
			writeDebugLog(loc, msg.toString());
		}
	}
	
	public void checkAndroid11SketchbookAccess(Activity activity, boolean forceUpdate) {
		if (android.os.Build.VERSION.SDK_INT >= SAF_API_CUTOFF) {
			if (getSafStorageDrive() == null || forceUpdate) {
				// Need to get user to set SAF storage drive
				try {
					// Need to use storage access framework to access sketchbook on Android 11+.
					// As far as I'm concerned, Google is dumb as bricks for making this a requirement.
					
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
					// TODO: we need to have a separate path for upgrading from an older version of APDE
					MaybeDocumentFile existing = getSketchbookFolder();
					if (existing.exists()) {
						intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, existing.resolve().getUri());
					}
					activity.startActivityForResult(intent, FLAG_SELECT_SKETCHBOOK_FOLDER);
				} catch (MaybeDocumentFile.MaybeDocumentFileException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
