package com.example.fajloteka;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "fajloteka_data";
    private static final String PREF_DATA = "saved_data";
    private static final int MENU_ADD_FILE = 100001;
    private static final int MENU_ADD_COLLECTION = 100002;
    private static final int MENU_CHOOSE_COLLECTION = 100003;
    private static final int MENU_EXIT_APP = 100004;

    private final ArrayList<FileCollection> collections = new ArrayList<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private FileCollection currentCollection;
    private TextView collectionNameView;
    private LinearLayout fileListView;
    private MaterialCardView emptyStateView;
    private ActivityResultLauncher<String[]> filePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return windowInsets;
        });

        collectionNameView = findViewById(R.id.text_collection_name);
        fileListView = findViewById(R.id.file_list);
        emptyStateView = findViewById(R.id.empty_state);

        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                this::importSelectedFiles
        );

        findViewById(R.id.button_add_files).setOnClickListener(this::showMainMenu);
        findViewById(R.id.button_add_files_empty).setOnClickListener(view -> openFilePicker());
        findViewById(R.id.button_collections).setOnClickListener(view -> showCollectionChooser());

        loadSavedData();
        renderCurrentCollection();
    }

    private void openFilePicker() {
        filePicker.launch(new String[]{"application/pdf", "image/*"});
    }

    private void loadSavedData() {
        String savedJson = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_DATA, null);
        boolean migratedDefaultName = false;
        if (savedJson == null) {
            collections.add(new FileCollection(UUID.randomUUID().toString(), "Library"));
            currentCollection = collections.get(0);
            saveData();
            return;
        }

        try {
            JSONObject root = new JSONObject(savedJson);
            JSONArray savedCollections = root.optJSONArray("collections");
            if (savedCollections != null) {
                for (int i = 0; i < savedCollections.length(); i++) {
                    JSONObject savedCollection = savedCollections.optJSONObject(i);
                    if (savedCollection == null) {
                        continue;
                    }

                    String id = savedCollection.optString("id", UUID.randomUUID().toString());
                    String name = savedCollection.optString("name", "Library");
                    if (i == 0 && "Moja prva kolekcija".equals(name)) {
                        name = "Library";
                        migratedDefaultName = true;
                    }
                    FileCollection collection = new FileCollection(id, name);

                    JSONArray savedFiles = savedCollection.optJSONArray("files");
                    if (savedFiles != null) {
                        for (int j = 0; j < savedFiles.length(); j++) {
                            JSONObject savedFile = savedFiles.optJSONObject(j);
                            if (savedFile == null) {
                                continue;
                            }
                            collection.files.add(new FileItem(
                                    savedFile.optString("name", "Fajl"),
                                    savedFile.optString("storedName", ""),
                                    savedFile.optString("mimeType", "application/octet-stream"),
                                    savedFile.optLong("size", 0)
                            ));
                        }
                    }
                    collections.add(collection);
                }
            }

            if (collections.isEmpty()) {
                collections.add(new FileCollection(UUID.randomUUID().toString(), "Library"));
            }

            String currentId = root.optString("currentCollectionId", collections.get(0).id);
            currentCollection = findCollection(currentId);
            if (currentCollection == null) {
                currentCollection = collections.get(0);
            }
            if (migratedDefaultName) {
                saveData();
            }
        } catch (JSONException exception) {
            collections.clear();
            currentCollection = new FileCollection(UUID.randomUUID().toString(), "Library");
            collections.add(currentCollection);
            saveData();
        }
    }

    private void saveData() {
        JSONObject root = new JSONObject();
        JSONArray collectionArray = new JSONArray();

        try {
            for (FileCollection collection : collections) {
                JSONObject savedCollection = new JSONObject();
                savedCollection.put("id", collection.id);
                savedCollection.put("name", collection.name);

                JSONArray files = new JSONArray();
                for (FileItem file : collection.files) {
                    JSONObject savedFile = new JSONObject();
                    savedFile.put("name", file.displayName);
                    savedFile.put("storedName", file.storedName);
                    savedFile.put("mimeType", file.mimeType);
                    savedFile.put("size", file.sizeBytes);
                    files.put(savedFile);
                }

                savedCollection.put("files", files);
                collectionArray.put(savedCollection);
            }

            root.put("currentCollectionId", currentCollection == null ? "" : currentCollection.id);
            root.put("collections", collectionArray);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_DATA, root.toString())
                    .apply();
        } catch (JSONException exception) {
            Toast.makeText(this, "Nije moguće sačuvati promene.", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderCurrentCollection() {
        if (currentCollection == null) {
            return;
        }
        collectionNameView.setText(currentCollection.name);
        renderFiles();
    }

    private void renderFiles() {
        fileListView.removeAllViews();
        boolean isEmpty = currentCollection.files.isEmpty();
        emptyStateView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        fileListView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);

        for (FileItem file : currentCollection.files) {
            fileListView.addView(createFileCard(file));
        }
    }

    private View createFileCard(FileItem file) {
        LinearLayout itemContainer = new LinearLayout(this);
        itemContainer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, dp(14));

        Bitmap thumbnail = (isImage(file) || isPdf(file)) ? loadThumbnail(file) : null;
        if (thumbnail != null) {
            ImageView image = new ImageView(this);
            image.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(92)));
            image.setScaleType(isPdf(file) ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            image.setBackground(roundedBackground("#F4F5F4", 3));
            image.setClipToOutline(true);
            image.setContentDescription(file.displayName);
            image.setImageBitmap(thumbnail);
            row.addView(image);
        } else {
            TextView fileBadge = new TextView(this);
            fileBadge.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(92)));
            fileBadge.setGravity(Gravity.CENTER);
            fileBadge.setText(isPdf(file) ? "PDF" : (isImage(file) ? "IMG" : "FILE"));
            fileBadge.setTextColor(isPdf(file) ? Color.parseColor("#B94C3A") : Color.parseColor("#18734F"));
            fileBadge.setTextSize(12);
            fileBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            fileBadge.setBackground(roundedBackground(
                    isPdf(file) ? "#FCEDE9" : "#E8F4ED",
                    3
            ));
            row.addView(fileBadge);
        }

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(16), 0, dp(8), 0);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        textColumn.setLayoutParams(textParams);

        TextView name = new TextView(this);
        name.setText(file.displayName);
        name.setTextColor(Color.parseColor("#111111"));
        name.setTextSize(18);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textColumn.addView(name);

        TextView details = new TextView(this);
        details.setText(fileKind(file) + "  •  " + formatBytes(file.sizeBytes));
        details.setTextColor(Color.parseColor("#8A8A8A"));
        details.setTextSize(14);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(5);
        details.setLayoutParams(detailParams);
        textColumn.addView(details);
        row.addView(textColumn);

        TextView moreButton = new TextView(this);
        moreButton.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(52)));
        moreButton.setGravity(Gravity.CENTER);
        moreButton.setText("•••");
        moreButton.setTextColor(Color.parseColor("#8A8A8A"));
        moreButton.setTextSize(15);
        moreButton.setContentDescription("Opcije za " + file.displayName);
        moreButton.setOnClickListener(view -> showFileMenu(view, file));
        row.addView(moreButton);

        row.setContentDescription(file.displayName + ", " + fileKind(file) + ". Otvori fajl.");
        row.setOnClickListener(view -> openFile(file));
        itemContainer.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        dividerParams.bottomMargin = dp(3);
        itemContainer.addView(divider, dividerParams);
        return itemContainer;
    }
    private boolean isImage(FileItem file) {
        if (file.mimeType != null && file.mimeType.startsWith("image/")) {
            return true;
        }
        String name = file.displayName.toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp")
                || name.endsWith(".heic") || name.endsWith(".heif");
    }

    private Bitmap loadThumbnail(FileItem item) {
        if (currentCollection == null) {
            return null;
        }
        File sourceFile = getStoredFile(currentCollection.id, item.storedName);
        if (!sourceFile.exists()) {
            return null;
        }

        int targetWidth = dp(64);
        int targetHeight = dp(92);
        if (isPdf(item)) {
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(descriptor)) {
                if (renderer.getPageCount() == 0) {
                    return null;
                }
                try (PdfRenderer.Page page = renderer.openPage(0)) {
                    float scale = Math.min(
                            targetWidth / (float) page.getWidth(),
                            targetHeight / (float) page.getHeight()
                    );
                    int width = Math.max(1, Math.round(page.getWidth() * scale));
                    int height = Math.max(1, Math.round(page.getHeight() * scale));
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return bitmap;
                }
            } catch (IOException | RuntimeException exception) {
                return null;
            }
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > targetWidth * 2
                || bounds.outHeight / sampleSize > targetHeight * 2) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), options);
    }

    private void showMainMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(Menu.NONE, MENU_ADD_FILE, 0, "Dodaj novi fajl");
        popup.getMenu().add(Menu.NONE, MENU_ADD_COLLECTION, 1, "Dodaj novu kolekciju");
        popup.getMenu().add(Menu.NONE, MENU_CHOOSE_COLLECTION, 2, "Izaberi kolekciju");
        popup.getMenu().add(Menu.NONE, MENU_EXIT_APP, 3, R.string.exit_app);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_ADD_FILE) {
                openFilePicker();
                return true;
            }
            if (item.getItemId() == MENU_ADD_COLLECTION) {
                showCreateCollectionDialog();
                return true;
            }
            if (item.getItemId() == MENU_CHOOSE_COLLECTION) {
                showCollectionChooser();
                return true;
            }
            if (item.getItemId() == MENU_EXIT_APP) {
                finish();
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showCollectionChooser() {
        if (collections.isEmpty()) {
            return;
        }

        String[] names = new String[collections.size()];
        int selectedIndex = 0;
        for (int i = 0; i < collections.size(); i++) {
            names[i] = collections.get(i).name;
            if (currentCollection != null && collections.get(i).id.equals(currentCollection.id)) {
                selectedIndex = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Izaberi kolekciju")
                .setSingleChoiceItems(names, selectedIndex, (dialog, which) -> {
                    currentCollection = collections.get(which);
                    saveData();
                    renderCurrentCollection();
                    dialog.dismiss();
                })
                .setNegativeButton("Zatvori", null)
                .show();
    }
    private void showCreateCollectionDialog() {
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("Na primer: Dokumenta");
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        new AlertDialog.Builder(this)
                .setTitle("Nova kolekcija")
                .setMessage("Napravi kolekciju za posebnu grupu fajlova.")
                .setView(nameInput, dp(24), dp(4), dp(24), 0)
                .setNegativeButton("Otkaži", null)
                .setPositiveButton("Napravi", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Unesi naziv kolekcije.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    for (FileCollection collection : collections) {
                        if (collection.name.equalsIgnoreCase(name)) {
                            Toast.makeText(this, "Kolekcija sa tim nazivom već postoji.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }

                    currentCollection = new FileCollection(UUID.randomUUID().toString(), name);
                    collections.add(currentCollection);
                    saveData();
                    renderCurrentCollection();
                })
                .show();
    }

    private void showFileMenu(View anchor, FileItem file) {
        PopupMenu popup = new PopupMenu(this, anchor);
        MenuItem remove = popup.getMenu().add("Ukloni iz kolekcije");
        remove.setOnMenuItemClickListener(item -> {
            confirmRemoveFile(file);
            return true;
        });
        popup.show();
    }

    private void confirmRemoveFile(FileItem file) {
        new AlertDialog.Builder(this)
                .setTitle("Ukloniti fajl?")
                .setMessage("„" + file.displayName + "“ će biti uklonjen iz ove kolekcije i sačuvana kopija će biti obrisana.")
                .setNegativeButton("Otkaži", null)
                .setPositiveButton("Ukloni", (dialog, which) -> {
                    FileCollection owner = currentCollection;
                    if (owner == null || !owner.files.remove(file)) {
                        return;
                    }
                    getStoredFile(owner.id, file.storedName).delete();
                    saveData();
                    renderCurrentCollection();
                })
                .show();
    }

    private void importSelectedFiles(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || currentCollection == null) {
            return;
        }

        String targetCollectionId = currentCollection.id;
        String targetCollectionName = currentCollection.name;
        Toast.makeText(this, "Čuvam izabrane fajlove…", Toast.LENGTH_SHORT).show();

        ioExecutor.execute(() -> {
            ArrayList<FileItem> importedFiles = new ArrayList<>();
            int failedCount = 0;

            for (Uri uri : uris) {
                try {
                    importedFiles.add(copyFileIntoCollection(uri, targetCollectionId));
                } catch (IOException exception) {
                    failedCount++;
                }
            }

            int finalFailedCount = failedCount;
            runOnUiThread(() -> {
                FileCollection target = findCollection(targetCollectionId);
                if (target != null && !importedFiles.isEmpty()) {
                    target.files.addAll(importedFiles);
                    saveData();
                }

                if (!isFinishing() && !isDestroyed()) {
                    if (currentCollection != null && currentCollection.id.equals(targetCollectionId)) {
                        renderCurrentCollection();
                    }

                    if (!importedFiles.isEmpty()) {
                        String message = formatFileCount(importedFiles.size()) + " dodato u „" + targetCollectionName + "“.";
                        if (finalFailedCount > 0) {
                            message += " " + formatFileCount(finalFailedCount) + " nije moglo da se sačuva.";
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Nije uspelo čuvanje izabranih fajlova.", Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
    }

    private FileItem copyFileIntoCollection(Uri uri, String collectionId) throws IOException {
        String displayName = queryDisplayName(uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = "Dokument";
        }
        displayName = sanitizeDisplayName(displayName);

        String mimeType = getContentResolver().getType(uri);
        if (mimeType == null || mimeType.trim().isEmpty()) {
            mimeType = guessMimeType(displayName);
        }

        File directory = new File(new File(getFilesDir(), "saved_files"), collectionId);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Ne mogu da napravim folder za fajlove.");
        }

        String extension = "";
        int dot = displayName.lastIndexOf('.');
        if (dot > 0 && dot < displayName.length() - 1) {
            extension = displayName.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        }

        File destination = new File(directory, UUID.randomUUID().toString() + extension);
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Fajl nije dostupan.");
            }
            try (OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
                output.flush();
            }
        } catch (IOException exception) {
            destination.delete();
            throw exception;
        }

        return new FileItem(displayName, destination.getName(), mimeType, destination.length());
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameColumn >= 0) {
                    return cursor.getString(nameColumn);
                }
            }
        } catch (Exception ignored) {
            // The URI may come from a provider that does not expose display metadata.
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return uri.getLastPathSegment();
    }

    private String sanitizeDisplayName(String name) {
        String normalized = name.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0) {
            normalized = normalized.substring(lastSlash + 1);
        }
        normalized = normalized.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        return normalized.isEmpty() ? "Dokument" : normalized;
    }

    private String guessMimeType(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lowerName.endsWith(".png")) {
            return "image/png";
        }
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerName.endsWith(".webp")) {
            return "image/webp";
        }
        if (lowerName.endsWith(".gif")) {
            return "image/gif";
        }
        if (lowerName.endsWith(".heic") || lowerName.endsWith(".heif")) {
            return "image/heic";
        }
        return "application/octet-stream";
    }

    private void openFile(FileItem item) {
        File file = getStoredFile(currentCollection.id, item.storedName);
        if (!file.exists()) {
            Toast.makeText(this, "Sačuvana kopija nije pronađena. Dodaj fajl ponovo.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    file
            );
            String mimeType = item.mimeType;
            if (mimeType == null || mimeType.trim().isEmpty() || mimeType.equals("application/octet-stream")) {
                mimeType = guessMimeType(item.displayName);
            }

            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(uri, mimeType);
            viewIntent.setClipData(ClipData.newUri(getContentResolver(), item.displayName, uri));
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(viewIntent, "Otvori fajl"));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "Nema aplikacije koja može da otvori ovaj fajl.", Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, "Ne mogu da otvorim ovaj fajl.", Toast.LENGTH_LONG).show();
        }
    }

    private File getStoredFile(String collectionId, String storedName) {
        return new File(new File(new File(getFilesDir(), "saved_files"), collectionId), storedName);
    }

    private FileCollection findCollection(String id) {
        for (FileCollection collection : collections) {
            if (collection.id.equals(id)) {
                return collection;
            }
        }
        return null;
    }

    private String fileKind(FileItem file) {
        if (isPdf(file)) {
            return "PDF";
        }
        if (isImage(file)) {
            return "SLIKA";
        }
        return "FAJL";
    }

    private boolean isPdf(FileItem file) {
        return (file.mimeType != null && file.mimeType.equalsIgnoreCase("application/pdf"))
                || file.displayName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String formatFileCount(int count) {
        int lastTwoDigits = count % 100;
        int lastDigit = count % 10;
        if (lastDigit == 1 && lastTwoDigits != 11) {
            return count + " fajl";
        }
        if (lastDigit >= 2 && lastDigit <= 4 && (lastTwoDigits < 12 || lastTwoDigits > 14)) {
            return count + " fajla";
        }
        return count + " fajlova";
    }

    private GradientDrawable roundedBackground(String color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdown();
    }

    private static final class FileCollection {
        final String id;
        final String name;
        final ArrayList<FileItem> files = new ArrayList<>();

        FileCollection(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class FileItem {
        final String displayName;
        final String storedName;
        final String mimeType;
        final long sizeBytes;

        FileItem(String displayName, String storedName, String mimeType, long sizeBytes) {
            this.displayName = displayName;
            this.storedName = storedName;
            this.mimeType = mimeType;
            this.sizeBytes = sizeBytes;
        }
    }
}
