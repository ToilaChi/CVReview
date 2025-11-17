package org.example.recruitmentservice.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.example.recruitmentservice.dto.response.DriveFileInfo;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final GoogleDriveService googleDriveService;

    /**
     * Upload JD file lên Google Drive
     * @return DriveFileInfo chứa fileId và links
     */
    public DriveFileInfo uploadJD(MultipartFile file, String name, String language, String level) {
        try {
            if (file == null || file.isEmpty()) {
                throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
            }

            // Build folder path tương tự như trước: name/language/level
            String folderPath = buildFolderPath(name, language, level);

            log.info("Uploading JD to Drive: {}", folderPath);
            return googleDriveService.uploadFile(file, folderPath);

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to upload JD: {}", e.getMessage());
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

    /**
     * Upload CV file lên Google Drive
     * @param file File cần upload
     * @param folderPath Đường dẫn folder (vd: "Backend/Java/Senior/CV" hoặc "candidate-cvs/2025/01")
     * @return DriveFileInfo
     */
    public DriveFileInfo uploadCV(MultipartFile file, String folderPath) {
        try {
            if (file == null || file.isEmpty()) {
                throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
            }

            log.info("Uploading CV to Drive: {}", folderPath);
            return googleDriveService.uploadFile(file, folderPath);

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to upload CV: {}", e.getMessage());
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

    /**
     * Download file từ Drive về temp để parse
     * @param fileId Google Drive file ID
     * @return Absolute path của file temp
     */
    public String downloadFileToTemp(String fileId) {
        try {
            return googleDriveService.downloadFileToTemp(fileId);
        } catch (Exception e) {
            log.error("Failed to download file: {}", e.getMessage());
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
        }
    }

    /**
     * Xóa file trên Drive
     */
    public void deleteFile(String fileId) {
        try {
            if (fileId == null || fileId.isEmpty()) {
                throw new CustomException(ErrorCode.FILE_NOT_FOUND);
            }
            googleDriveService.deleteFile(fileId);
        } catch (Exception e) {
            log.error("Failed to delete file: {}", e.getMessage());
            throw new CustomException(ErrorCode.FILE_DELETE_FAILED);
        }
    }

    /**
     * Move JD file sang folder mới trên Drive
     */
    public DriveFileInfo moveJD(String fileId, String newName, String newLang, String newLevel) {
        try {
            String newFolderPath = buildFolderPath(newName, newLang, newLevel);

            if (newFolderPath.isEmpty()) {
                // Không có gì để move
                return null;
            }

            log.info("Moving JD on Drive to: {}", newFolderPath);
            return googleDriveService.moveFile(fileId, newFolderPath);

        } catch (Exception e) {
            log.error("Failed to move JD: {}", e.getMessage());
            throw new CustomException(ErrorCode.FILE_MOVE_FAILED);
        }
    }

    /**
     * Xóa temp file sau khi parse xong
     */
    public void deleteTempFile(String tempFilePath) {
        try {
            Path path = Paths.get(tempFilePath);
            if (Files.exists(path)) {
                Files.delete(path);
                log.info("Deleted temp file: {}", tempFilePath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete temp file: {}", e.getMessage());
            // Không throw exception vì đây chỉ là cleanup
        }
    }

    /**
     * Build folder path: name/language/level
     */
    private String buildFolderPath(String name, String language, String level) {
        StringBuilder path = new StringBuilder();

        if (name != null && !name.isBlank()) {
            path.append(name.trim());
        }
        if (language != null && !language.isBlank()) {
            if (!path.isEmpty()) path.append("/");
            path.append(language.trim());
        }
        if (level != null && !level.isBlank()) {
            if (!path.isEmpty()) path.append("/");
            path.append(level.trim());
        }

        return path.toString();
    }

    // ==== DEPRECATED METHODS (for backward compatibility) ====
    // Có thể xóa sau khi migration hoàn tất

    @Deprecated
    public String getAbsolutePath(String relativePath) {
        log.warn("getAbsolutePath() is deprecated. Use downloadFileToTemp() instead.");
        // Tạm thời giữ để không break code, nhưng sẽ xóa sau
        throw new UnsupportedOperationException("This method is deprecated. Use downloadFileToTemp() with fileId instead.");
    }

    @Deprecated
    public void saveFile(MultipartFile file, String relativePath) {
        log.warn("saveFile() is deprecated. Use uploadCV() instead.");
        throw new UnsupportedOperationException("This method is deprecated. Use uploadCV() instead.");
    }
}