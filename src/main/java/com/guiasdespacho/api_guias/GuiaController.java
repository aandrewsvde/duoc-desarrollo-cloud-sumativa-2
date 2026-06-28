package com.guiasdespacho.api_guias;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/guias")
public class GuiaController {

    private final S3Client s3Client;
    @Value("${aws.s3.bucket-name}") private String bucketName;
    @Value("${storage.local-efs-path}") private String efsPath;

    public GuiaController(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @PostMapping("/upload")
    public ResponseEntity<String> uploadGuia(@RequestParam("file") MultipartFile file,
                                            @RequestParam("fecha") String fecha,
                                            @RequestParam("transportista") String transportista) {
        try {
            String fileName = file.getOriginalFilename();
            Path localPath = Paths.get(efsPath, fileName);
            Files.createDirectories(localPath.getParent());
            file.transferTo(localPath.toFile());

            String s3Key = fecha + "/" + transportista + "/" + fileName;

            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(bucketName).key(s3Key).build(),
                    RequestBody.fromFile(localPath.toFile()));

            return ResponseEntity.ok("Guía procesada en EFS y S3 bajo la ruta: " + s3Key);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    @PutMapping("/update")
    public ResponseEntity<String> updateGuia(@RequestParam("file") MultipartFile file,
                                            @RequestParam("fecha") String fecha,
                                            @RequestParam("transportista") String transportista) {
        return uploadGuia(file, fecha, transportista);
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadGuia(@RequestParam("fecha") String fecha,
                                            @RequestParam("transportista") String transportista,
                                            @RequestParam("archivo") String archivo) {
        String s3Key = fecha + "/" + transportista + "/" + archivo;
        try {
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + archivo + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(objectBytes.asByteArray());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
    }

    @GetMapping("/historial")
    public ResponseEntity<List<String>> getHistorial(@RequestParam("fecha") String fecha,
                                                    @RequestParam("transportista") String transportista) {
        String prefix = fecha + "/" + transportista + "/";
        try {
            ListObjectsV2Response result = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix).build());

            List<String> archivos = result.contents().stream()
                    .map(S3Object::key)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(archivos);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<String> deleteGuia(@RequestParam("fecha") String fecha,
                                            @RequestParam("transportista") String transportista,
                                            @RequestParam("archivo") String archivo) {
        String s3Key = fecha + "/" + transportista + "/" + archivo;
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            return ResponseEntity.ok("Archivo " + s3Key + " eliminado de S3 con éxito");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }
}