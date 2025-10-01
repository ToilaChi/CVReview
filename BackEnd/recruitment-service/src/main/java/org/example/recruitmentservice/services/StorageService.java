package org.example.recruitmentservice.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

//@Service
//public class StorageService {
//
//    private final S3Client s3Client;
//
//    @Value("${r2.bucket-name}")
//    private String bucketName;
//
//    @Value("${r2.public-url}")
//    private String publicUrl; // https://<accountid>.r2.cloudflarestorage.com/<bucket>
//
//    public StorageService(
//            @Value("${r2.account-id}") String accountId,
//            @Value("${r2.access-key}") String accessKey,
//            @Value("${r2.secret-key}") String secretKey,
//            @Value("${r2.region}") String region
//    ) {
//        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
//        this.s3Client = S3Client.builder()
//                .region(Region.of(region))
//                .endpointOverride(URI.create("https://" + accountId + ".r2.cloudflarestorage.com"))
//                .credentialsProvider(StaticCredentialsProvider.create(credentials))
//                .build();
//    }
//
//    public String uploadFile(MultipartFile file, String folder) {
//        try {
//            String key = folder + "/" + UUID.randomUUID() + "-" + file.getOriginalFilename();
//
//            PutObjectRequest putRequest = PutObjectRequest.builder()
//                    .bucket(bucketName)
//                    .key(key)
//                    .contentType(file.getContentType())
//                    .build();
//
//            s3Client.putObject(putRequest, RequestBody.fromBytes(file.getBytes()));
//
//            // Trả về public URL
//            return publicUrl + "/" + key;
//
//        } catch (IOException e) {
//            throw new RuntimeException("Failed to upload file to R2", e);
//        }
//    }
//}

@Service
public class StorageService {

    @Value("${storage.local.base-path}")
    private String basePath;

    @Value("${server.port}")
    private String serverPort;

    public String uploadFile(MultipartFile file, String folder) {
        try {
            // Tạo thư mục nếu chưa có
            Path folderPath = Paths.get(basePath, folder);
            Files.createDirectories(folderPath);

            // Tạo tên file unique
            String fileName = UUID.randomUUID() + "-" + file.getOriginalFilename();
            Path filePath = folderPath.resolve(fileName);

            // Lưu file
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            System.out.println("✅ File saved to: " + filePath.toAbsolutePath());

            // Trả về absolute path để LlamaParse đọc file local
            return filePath.toAbsolutePath().toString();

        } catch (IOException e) {
            throw new RuntimeException("Failed to save file locally", e);
        }
    }
}
