package org.example.recruitmentservice.services;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.dto.response.DriveFileInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
@Slf4j
public class GoogleDriveService {

    @Value("${OAUTH_CLIENT_ID}")
    private String clientId;

    @Value("${OAUTH_CLIENT_SECRET}")
    private String clientSecret;

    @Value("${GOOGLE_REFRESH_TOKEN}")
    private String refreshToken;

    @Value("${google.drive.folder-id}")
    private String rootFolderId;

    private Drive driveService;

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "Recruitment Service";

    @PostConstruct
    public void init() throws Exception {
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        JsonFactory jsonFactory = GsonFactory.getDefaultInstance();

        // Build credential manually
        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(transport)
                .setJsonFactory(jsonFactory)
                .setClientAuthentication(new ClientParametersAuthentication(clientId, clientSecret))
                .setTokenServerUrl(new GenericUrl("https://oauth2.googleapis.com/token")) // <-- important
                .build();

        credential.setRefreshToken(refreshToken);

        // Refresh token immediately
        boolean success = credential.refreshToken();
        if (!success) {
            throw new RuntimeException("FAILED TO REFRESH GOOGLE DRIVE TOKEN. Refresh token expired or revoked.");
        }

        // Build Drive client
        driveService = new Drive.Builder(transport, jsonFactory, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();

        log.info("Google Drive initialized successfully");
    }

    /**
     Upload file lên Google Drive
     */
    public DriveFileInfo uploadFile(MultipartFile multipartFile, String folderPath) {
        try {
            // Tạo folder structure nếu chưa tồn tại
            String parentFolderId = createFolderStructure(folderPath);

            // Tạo file metadata
            File fileMetadata = new File();
            fileMetadata.setName(multipartFile.getOriginalFilename());
            fileMetadata.setParents(Collections.singletonList(parentFolderId));

            // Convert MultipartFile sang java.io.File tạm
            java.io.File tempFile = convertMultipartFileToFile(multipartFile);

            // Upload lên Drive
            FileContent mediaContent = new FileContent(
                    multipartFile.getContentType(),
                    tempFile
            );

            File uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id, name, webViewLink, webContentLink")
                    .execute();

            // Xóa temp file
            tempFile.delete();

            log.info("Uploaded file to Drive: {} (ID: {})", uploadedFile.getName(), uploadedFile.getId());

            return new DriveFileInfo(
                    uploadedFile.getId(),
                    uploadedFile.getName(),
                    uploadedFile.getWebViewLink(),
                    uploadedFile.getWebContentLink()
            );

        } catch (Exception e) {
            log.error("Failed to upload file to Drive: {}", e.getMessage());
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

    /**
     * Download file từ Drive về local temp
     * @param fileId Google Drive file ID
     * @return Absolute path của file temp
     */
    public String downloadFileToTemp(String fileId) {
        try {
            File file = driveService.files().get(fileId)
                    .setFields("id, name")
                    .execute();

            // Tạo temp file
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "recruitment-temp");
            Files.createDirectories(tempDir);

            Path tempFile = tempDir.resolve(UUID.randomUUID() + "-" + file.getName());

            // Download
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                driveService.files().get(fileId).executeMediaAndDownloadTo(fos);
            }

            log.info("Downloaded file from Drive: {} to {}", file.getName(), tempFile);
            return tempFile.toAbsolutePath().toString();

        } catch (Exception e) {
            log.error("Failed to download file from Drive: {}", e.getMessage());
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
        }
    }

    /**
     * Xóa file trên Drive
     */
    public void deleteFile(String fileId) {
        try {
            driveService.files().delete(fileId).execute();
            log.info("Deleted file from Drive: {}", fileId);
        } catch (Exception e) {
            log.error("Failed to delete file from Drive: {}", e.getMessage());
            throw new CustomException(ErrorCode.FILE_DELETE_FAILED);
        }
    }

    /**
     * Move file sang folder khác trên Drive
     */
    public DriveFileInfo moveFile(String fileId, String newFolderPath) {
        try {
            // Get current file
            File file = driveService.files().get(fileId)
                    .setFields("id, name, parents")
                    .execute();

            // Tạo folder mới
            String newParentFolderId = createFolderStructure(newFolderPath);

            // Remove old parents
            StringBuilder previousParents = new StringBuilder();
            for (String parent : file.getParents()) {
                previousParents.append(parent).append(',');
            }

            // Move file
            File updatedFile = driveService.files().update(fileId, null)
                    .setAddParents(newParentFolderId)
                    .setRemoveParents(previousParents.toString())
                    .setFields("id, name, webViewLink, webContentLink")
                    .execute();

            log.info("Moved file on Drive: {} to folder {}", file.getName(), newFolderPath);

            return new DriveFileInfo(
                    updatedFile.getId(),
                    updatedFile.getName(),
                    updatedFile.getWebViewLink(),
                    updatedFile.getWebContentLink()
            );

        } catch (Exception e) {
            log.error("Failed to move file on Drive: {}", e.getMessage());
            throw new CustomException(ErrorCode.FILE_MOVE_FAILED);
        }
    }

    /**
     * Tạo folder structure trên Drive (nested folders)
     */
    private String createFolderStructure(String folderPath) throws IOException {
        if (folderPath == null || folderPath.trim().isEmpty()) {
            return rootFolderId;
        }

        String[] folders = folderPath.split("/");
        String currentParentId = rootFolderId;

        for (String folderName : folders) {
            if (folderName.trim().isEmpty()) continue;

            // Kiểm tra folder đã tồn tại chưa
            String existingFolderId = findFolder(folderName, currentParentId);

            if (existingFolderId != null) {
                currentParentId = existingFolderId;
            } else {
                // Tạo folder mới
                File folderMetadata = new File();
                folderMetadata.setName(folderName);
                folderMetadata.setMimeType("application/vnd.google-apps.folder");
                folderMetadata.setParents(Collections.singletonList(currentParentId));

                File createdFolder = driveService.files().create(folderMetadata)
                        .setFields("id")
                        .execute();

                currentParentId = createdFolder.getId();
                log.info("Created folder on Drive: {} (ID: {})", folderName, currentParentId);
            }
        }

        return currentParentId;
    }

    /**
     * Tìm folder theo tên và parent
     */
    private String findFolder(String folderName, String parentId) throws IOException {
        String query = String.format(
                "name='%s' and mimeType='application/vnd.google-apps.folder' and '%s' in parents and trashed=false",
                folderName.replace("'", "\\'"),
                parentId
        );

        FileList result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute();

        List<File> files = result.getFiles();
        return files.isEmpty() ? null : files.get(0).getId();
    }

    /**
     * Convert MultipartFile sang java.io.File
     */
    private java.io.File convertMultipartFileToFile(MultipartFile multipartFile) throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "recruitment-upload");
        Files.createDirectories(tempDir);

        java.io.File file = tempDir.resolve(UUID.randomUUID() + "-" + multipartFile.getOriginalFilename()).toFile();
        multipartFile.transferTo(file);
        return file;
    }
}
