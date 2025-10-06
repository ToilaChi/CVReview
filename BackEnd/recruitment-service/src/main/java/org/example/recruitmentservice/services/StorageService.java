package org.example.recruitmentservice.services;

import org.example.commonlibrary.dto.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class StorageService {

    @Value("${storage.local.base-path}")
    private String basePath;

    public String uploadFile(MultipartFile file, String name, String language, String level) {
        try {
            if (file == null || file.isEmpty()) {
                throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
            }

            Path relativePath = Paths.get(
                    name,
                    language != null ? language : "",
                    level != null ? level : ""
            ).normalize();

            // Build absolute folder path
            Path folderPath = Paths.get(basePath, relativePath.toString()).normalize();
            Files.createDirectories(folderPath);

            // Create file name unique
            String fileName = UUID.randomUUID() + "-" + file.getOriginalFilename();
            Path filePath = folderPath.resolve(fileName);

            // Save file
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            return relativePath.resolve(fileName).toString().replace("\\", "/");
        } catch (IOException e) {
            System.err.println("IOException: " + e.getMessage());
            e.printStackTrace();
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        } catch (Exception e) {
            System.err.println("Exception: " + e.getMessage());
            e.printStackTrace();
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

    // Get absolute path from relative path
    public String getAbsolutePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            throw new CustomException(ErrorCode.FILE_NOT_FOUND);
        }
        return Paths.get(basePath, relativePath).toAbsolutePath().toString();
    }

    public void deleteFile(String filePath) {
        try {
            if (filePath == null || filePath.isEmpty()) {
                throw new CustomException(ErrorCode.FILE_NOT_FOUND);
            }

            Path pathToDelete;

            if (filePath.matches("^[A-Za-z]:.*") || filePath.startsWith("/") || Paths.get(filePath).isAbsolute()) {
                pathToDelete = Paths.get(filePath).normalize();
            } else {
                pathToDelete = Paths.get(basePath, filePath).normalize();
            }

            if (Files.exists(pathToDelete)) {
                Files.delete(pathToDelete);
                System.out.println("Deleted old JD file: " + pathToDelete);
            } else {
                System.err.println("File not found for deletion: " + pathToDelete);
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw new CustomException(ErrorCode.FILE_DELETE_FAILED);
        }
    }
}