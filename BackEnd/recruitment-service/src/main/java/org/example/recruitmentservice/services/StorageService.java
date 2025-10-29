package org.example.recruitmentservice.services;

import org.example.commonlibrary.dto.response.ErrorCode;
import org.example.commonlibrary.exception.CustomException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class StorageService {

    @Value("${storage.local.base-path}")
    private String basePath;

    public String uploadJD(MultipartFile file, String name, String language, String level) {
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
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

    public String moveJD(String oldPath, String newName, String newLang, String newLevel) throws IOException {
        Path oldFile = Paths.get(basePath, oldPath);
        String fileName = oldFile.getFileName().toString();

        StringBuilder newRelativePath = new StringBuilder();

        if (newName != null && !newName.isBlank()) {
            newRelativePath.append(newName);
        }
        if (newLang != null && !newLang.isBlank()) {
            if (!newRelativePath.isEmpty()) newRelativePath.append("/");
            newRelativePath.append(newLang);
        }
        if (newLevel != null && !newLevel.isBlank()) {
            if (!newRelativePath.isEmpty()) newRelativePath.append("/");
            newRelativePath.append(newLevel);
        }

        // Nếu không có gì để move, giữ nguyên
        if (newRelativePath.isEmpty()) {
            return oldPath;
        }

        Path newDir = Paths.get(basePath, newRelativePath.toString()).normalize();
        Files.createDirectories(newDir);

        Path newFile = newDir.resolve(fileName);
        Files.move(oldFile, newFile, StandardCopyOption.REPLACE_EXISTING);

        return newRelativePath + "/" + fileName;
    }



    public void saveFile(MultipartFile file, String relativePath) {
        try {
            if (file == null || file.isEmpty()) {
                throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
            }

            Path normalizedPath = Paths.get(relativePath).normalize();

            if (normalizedPath.isAbsolute() || normalizedPath.startsWith("..")) {
                throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
            }

            Path target = Paths.get(basePath, normalizedPath.toString()).normalize();

            if (!target.startsWith(Paths.get(basePath).normalize())) {
                throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
            }

            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("IOException: " + e.getMessage());
            e.printStackTrace();
            throw new CustomException(ErrorCode.FAILED_SAVE_FILE);
        }
    }

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