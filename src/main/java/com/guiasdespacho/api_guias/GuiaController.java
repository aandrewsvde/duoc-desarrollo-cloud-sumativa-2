package com.guiasdespacho.api_guias;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/guias")
public class GuiaController {

    private final S3Client s3Client;
    @Value("${aws.s3.bucket-name}") private String bucketName;

    public GuiaController(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    // 1. Generar guía de despacho en PDF y subirla a S3
    @PostMapping("/crear")
    public ResponseEntity<String> crearGuia(@RequestParam("fecha") String fecha,
                                             @RequestParam("transportista") String transportista,
                                             @RequestParam("origen") String origen,
                                             @RequestParam("destino") String destino,
                                             @RequestParam("descripcion") String descripcion) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
            String numeroGuia = "GD-" + fecha.replace("-", "") + "-" + timestamp;
            String fileName = numeroGuia + ".pdf";
            String s3Key = fecha + "/" + transportista + "/" + fileName;

            byte[] pdf = generarPdfGuia(numeroGuia, fecha, transportista, origen, destino, descripcion);

            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType("application/pdf")
                    .build(),
                RequestBody.fromBytes(pdf)
            );

            return ResponseEntity.ok("Guía de despacho generada: " + s3Key);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // 2. Subir guía existente a S3
    @PostMapping("/subir")
    public ResponseEntity<String> subirGuia(@RequestParam("file") MultipartFile file,
                                             @RequestParam("fecha") String fecha,
                                             @RequestParam("transportista") String transportista) {
        try {
            String fileName = file.getOriginalFilename();
            String s3Key = fecha + "/" + transportista + "/" + fileName;

            s3Client.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(s3Key).build(),
                RequestBody.fromBytes(file.getBytes())
            );

            return ResponseEntity.ok("Guía subida a S3: " + s3Key);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // 3. Descargar guía desde S3
    @GetMapping("/descargar")
    public ResponseEntity<byte[]> descargarGuia(@RequestParam("fecha") String fecha,
                                                 @RequestParam("transportista") String transportista,
                                                 @RequestParam("archivo") String archivo) {
        String s3Key = fecha + "/" + transportista + "/" + archivo;
        try {
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucketName).key(s3Key).build()
            );
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + archivo + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(objectBytes.asByteArray());
        } catch (NoSuchKeyException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    // 4. Actualizar guía: mover a otro directorio en S3
    @PutMapping("/actualizar")
    public ResponseEntity<String> actualizarGuia(@RequestParam("archivo") String archivo,
                                                  @RequestParam("fechaOrigen") String fechaOrigen,
                                                  @RequestParam("transportistaOrigen") String transportistaOrigen,
                                                  @RequestParam("fechaDestino") String fechaDestino,
                                                  @RequestParam("transportistaDestino") String transportistaDestino) {
        String sourceKey = fechaOrigen + "/" + transportistaOrigen + "/" + archivo;
        String destKey   = fechaDestino + "/" + transportistaDestino + "/" + archivo;
        try {
            s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucketName).sourceKey(sourceKey)
                .destinationBucket(bucketName).destinationKey(destKey)
                .build());
            s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName).key(sourceKey).build());

            return ResponseEntity.ok("Guía movida de " + sourceKey + " a " + destKey);
        } catch (NoSuchKeyException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Guía no encontrada: " + sourceKey);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // 5. Eliminar guía específica de S3
    @DeleteMapping("/eliminar")
    public ResponseEntity<String> eliminarGuia(@RequestParam("fecha") String fecha,
                                               @RequestParam("transportista") String transportista,
                                               @RequestParam("archivo") String archivo) {
        String s3Key = fecha + "/" + transportista + "/" + archivo;
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(s3Key).build());
            return ResponseEntity.ok("Guía eliminada: " + s3Key);
        } catch (NoSuchKeyException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Guía no encontrada: " + s3Key);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // 6. Consultar guías filtrando por fecha y/o transportista
    @GetMapping("/consultar")
    public ResponseEntity<List<String>> consultarGuias(
            @RequestParam(value = "fecha", required = false) String fecha,
            @RequestParam(value = "transportista", required = false) String transportista) {
        try {
            String prefix = "";
            if (fecha != null && transportista != null) {
                prefix = fecha + "/" + transportista + "/";
            } else if (fecha != null) {
                prefix = fecha + "/";
            }

            ListObjectsV2Response result = s3Client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix).build()
            );

            List<String> archivos = result.contents().stream()
                .map(S3Object::key)
                .filter(key -> transportista == null || key.contains("/" + transportista + "/"))
                .collect(Collectors.toList());

            return ResponseEntity.ok(archivos);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    private byte[] generarPdfGuia(String numeroGuia, String fecha, String transportista,
                                    String origen, String destino, String descripcion) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 60, 50);
        PdfWriter.getInstance(doc, baos);
        doc.open();

        Font fTitulo  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
        Font fSeccion = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
        Font fNormal  = FontFactory.getFont(FontFactory.HELVETICA, 11);
        Font fPequena = FontFactory.getFont(FontFactory.HELVETICA, 9);

        // --- Encabezado ---
        Paragraph titulo = new Paragraph("GUÍA DE DESPACHO", fTitulo);
        titulo.setAlignment(Element.ALIGN_CENTER);
        doc.add(titulo);
        Paragraph subtitulo = new Paragraph("Documento Tributario de Traslado de Mercaderías", fPequena);
        subtitulo.setAlignment(Element.ALIGN_CENTER);
        doc.add(subtitulo);
        doc.add(new Paragraph(" "));

        // Línea separadora via tabla
        PdfPTable linea = new PdfPTable(1);
        linea.setWidthPercentage(100);
        PdfPCell celdaLinea = new PdfPCell();
        celdaLinea.setBorderWidthBottom(1f);
        celdaLinea.setBorderWidthTop(0);
        celdaLinea.setBorderWidthLeft(0);
        celdaLinea.setBorderWidthRight(0);
        celdaLinea.setMinimumHeight(2);
        linea.addCell(celdaLinea);
        doc.add(linea);
        doc.add(new Paragraph(" "));

        // --- N° Guía y Fecha ---
        PdfPTable tablaHeader = new PdfPTable(new float[]{2, 1});
        tablaHeader.setWidthPercentage(100);
        tablaHeader.addCell(celda("N° Guía:   " + numeroGuia, fSeccion, true));
        tablaHeader.addCell(celda("Fecha:   " + fecha, fNormal, true));
        doc.add(tablaHeader);
        doc.add(new Paragraph(" "));

        // --- Datos del Transporte ---
        PdfPTable tablaTransporte = new PdfPTable(1);
        tablaTransporte.setWidthPercentage(100);
        tablaTransporte.addCell(celdaEncabezado("DATOS DEL TRANSPORTE", fSeccion));
        tablaTransporte.addCell(celda("Transportista:   " + transportista, fNormal, true));
        doc.add(tablaTransporte);
        doc.add(new Paragraph(" "));

        // --- Ruta ---
        PdfPTable tablaRuta = new PdfPTable(2);
        tablaRuta.setWidthPercentage(100);
        tablaRuta.addCell(celdaEncabezado("ORIGEN", fSeccion));
        tablaRuta.addCell(celdaEncabezado("DESTINO", fSeccion));
        tablaRuta.addCell(celda(origen, fNormal, true));
        tablaRuta.addCell(celda(destino, fNormal, true));
        doc.add(tablaRuta);
        doc.add(new Paragraph(" "));

        // --- Descripción de la Carga ---
        PdfPTable tablaCarga = new PdfPTable(1);
        tablaCarga.setWidthPercentage(100);
        tablaCarga.addCell(celdaEncabezado("DESCRIPCIÓN DE LA CARGA", fSeccion));
        PdfPCell celdaCarga = new PdfPCell(new Phrase(descripcion, fNormal));
        celdaCarga.setPadding(8);
        celdaCarga.setMinimumHeight(70);
        tablaCarga.addCell(celdaCarga);
        doc.add(tablaCarga);
        doc.add(new Paragraph(" "));

        // --- Estado ---
        Paragraph estado = new Paragraph("ESTADO: EMITIDA", fSeccion);
        estado.setAlignment(Element.ALIGN_RIGHT);
        doc.add(estado);
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph(" "));
        doc.add(new Paragraph(" "));

        // --- Firmas ---
        PdfPTable tablaFirmas = new PdfPTable(2);
        tablaFirmas.setWidthPercentage(100);
        tablaFirmas.addCell(celda("Firma Remitente: _______________________", fNormal, false));
        tablaFirmas.addCell(celda("Firma Receptor: _______________________", fNormal, false));
        doc.add(tablaFirmas);

        doc.close();
        return baos.toByteArray();
    }

    private PdfPCell celda(String texto, Font fuente, boolean borde) {
        PdfPCell c = new PdfPCell(new Phrase(texto, fuente));
        c.setPadding(6);
        if (!borde) c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    private PdfPCell celdaEncabezado(String texto, Font fuente) {
        PdfPCell c = new PdfPCell(new Phrase(texto, fuente));
        c.setPadding(6);
        c.setBackgroundColor(new java.awt.Color(220, 220, 220));
        return c;
    }
}
