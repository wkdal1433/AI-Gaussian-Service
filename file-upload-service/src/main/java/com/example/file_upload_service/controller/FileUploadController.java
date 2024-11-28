package com.example.file_upload_service.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/upload")
public class FileUploadController {

    @Value("${file.upload-dir}")
    private String uploadRoot;

    @Value("${file.viewer-dir}")
    private String viewerRoot;

    @Value("${cloudflared.path}") // 클라우드플레어 실행 파일의 경로
    private String cloudflaredPath;

    private final ConcurrentHashMap<String, String> viewerUrlMap = new ConcurrentHashMap<>();
    private final Object fileLock = new Object();

    @PostMapping("/auto")
    public ResponseEntity<?> uploadFilesAndProcess(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "usePreGenerated", defaultValue = "false") boolean usePreGenerated
    ) {
        String clientId = UUID.randomUUID().toString();
        String clientUploadPath = uploadRoot + File.separator + clientId;
        String clientViewerPath = viewerRoot + File.separator + clientId;

        // 클라이언트별 디렉토리 생성
        new File(clientUploadPath).mkdirs();
        new File(clientViewerPath).mkdirs();

        try {
            String outputUUID = "";

            // 업로드된 파일 저장
            for (MultipartFile file : files) {
                File destination = new File(clientUploadPath, file.getOriginalFilename());
                file.transferTo(destination);
            }

            if (usePreGenerated) {
                // 테스트 모드: 준비된 .splat 파일 사용
                synchronized (fileLock) {
                    String preGeneratedFilePath = "templates/pre_generated.model_garden.splat";
                    try (InputStream preGeneratedSplatStream = getClass().getClassLoader().getResourceAsStream(preGeneratedFilePath)) {
                        if (preGeneratedSplatStream == null) {
                            throw new IllegalArgumentException("Pre-generated model file not found: " + preGeneratedFilePath);
                        }

                        File tempSplatFile = File.createTempFile("pre_generated_", ".splat");
                        Files.copy(preGeneratedSplatStream, tempSplatFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                        File destinationSplat = new File(clientViewerPath, "model.splat");
                        Files.copy(tempSplatFile.toPath(), destinationSplat.toPath(), StandardCopyOption.REPLACE_EXISTING);

                        tempSplatFile.delete(); // 임시 파일 삭제
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException("Failed to process pre-generated splat file.", e);
                    }
                }
            } else {
                // AI 모델 실행
                String clientInputPath = clientUploadPath + File.separator + "input";

                try {
                    // FFmpeg 명령 실행
                    File inputFolder = new File(clientInputPath);
                    if (!inputFolder.exists()) {
                        inputFolder.mkdirs();
                    }

                    for (MultipartFile file : files) {
                        File videoFile = new File(clientUploadPath, file.getOriginalFilename());
                        if (!videoFile.getName().endsWith(".mp4") && !videoFile.getName().endsWith(".avi") && !videoFile.getName().endsWith(".MOV")) {
                            throw new IllegalArgumentException("Uploaded file is not a supported video format.");
                        }

                        String videoFilePath = videoFile.getAbsolutePath();
                        String[] ffmpegCommand = {
                                "ffmpeg",
                                "-i", videoFilePath,
                                "-qscale:v", "1",
                                "-qmin", "1",
                                "-vf", "fps=3",
                                clientInputPath + File.separator + "%04d.jpg"
                        };

                        ProcessBuilder ffmpegpb = new ProcessBuilder(ffmpegCommand);
                        ffmpegpb.redirectErrorStream(true);
                        Process process = ffmpegpb.start();

                        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            System.out.println(line);
                        }

                        int exitCode = process.waitFor();
                        if (exitCode != 0) {
                            throw new RuntimeException("FFmpeg process failed with exit code: " + exitCode);
                        }
                    }

                    // Python 스크립트 convert.py 실행
                    String condaEnv = "gaussian_splatting";
                    String pythonScriptPathConvert = "C:/Users/user/Desktop/Gaussian/gaussian-splatting/convert.py";

                    String[] pythonCommandConvert = new String[]{"cmd.exe", "/c", "conda activate " + condaEnv + " && python " + pythonScriptPathConvert + " -s " + clientUploadPath};

                    ProcessBuilder convertpb = new ProcessBuilder(pythonCommandConvert);
                    convertpb.redirectErrorStream(true);
                    Process convertProcess = convertpb.start();

                    BufferedReader convertReader = new BufferedReader(new InputStreamReader(convertProcess.getInputStream()));
                    String line;
                    while ((line = convertReader.readLine()) != null) {
                        System.out.println(line);
                    }

                    int convertExitCode = convertProcess.waitFor();
                    if (convertExitCode != 0) {
                        throw new RuntimeException("convert.py script failed with exit code: " + convertExitCode);
                    }

                    // Python 스크립트 train.py 실행
                    String pythonScriptPathTrain = "C:/Users/user/Desktop/Gaussian/gaussian-splatting/train.py";

                    String[] pythonCommandTrain = new String[]{"cmd.exe", "/c", "conda activate " + condaEnv + " && python " + pythonScriptPathTrain + " -s " + clientUploadPath};

                    ProcessBuilder trainpb = new ProcessBuilder(pythonCommandTrain);
                    trainpb.redirectErrorStream(true);
                    Process trainProcess = trainpb.start();

                    BufferedReader trainReader = new BufferedReader(new InputStreamReader(trainProcess.getInputStream()));
                    while ((line = trainReader.readLine()) != null) {
                        System.out.println(line);
                        if (line.startsWith("Output folder:")) {
                            outputUUID = line.substring("Output folder: ./output/".length()).trim(); // 결과 경로 추출
                            System.out.println(outputUUID);
                        }
                    }

                    int trainExitCode = trainProcess.waitFor();
                    if (trainExitCode != 0) {
                        throw new RuntimeException("train.py script failed with exit code: " + trainExitCode);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException("Failed to execute AI model processing pipeline.", e);
                }
                //AI 모델 실행 끝
            }

            // 뷰어 파일 복사 및 clientId, outputUUID 동적 삽입
            try {
                // 템플릿 index.html 로드
                String indexTemplate = new String(Files.readAllBytes(Paths.get(viewerRoot + "/index.html")));

                // 템플릿 내 {{clientId}} 및 {{outputUUID}}를 실제 값으로 대체
                String populatedIndex = indexTemplate
                        .replace("{{clientId}}", clientId)
                        .replace("{{outputUUID}}", outputUUID);

                // 클라이언트별 index.html 저장
                Files.write(
                        Paths.get(clientViewerPath + "/index.html"),
                        populatedIndex.getBytes(),
                        StandardOpenOption.CREATE
                );

                // main.js 복사
                Files.copy(
                        new File(viewerRoot + File.separator + "main.js").toPath(),
                        new File(clientViewerPath + File.separator + "main.js").toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("Failed to create client-specific index.html", e);
            }


            // 클라이언트별 HTML 경로 반환
            String viewerUrl = "/viewer/" + clientId + "/index.html";
            return ResponseEntity.ok(new UploadResponse(clientId, viewerUrl, outputUUID));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("File upload or processing failed.");
        }
    }

    @GetMapping("/url/{clientId}")
    public ResponseEntity<String> getViewerUrl(@PathVariable String clientId) {
        String url = viewerUrlMap.get(clientId);
        if (url != null) {
            return ResponseEntity.ok(url);
        } else {
            return ResponseEntity.status(202).body("Processing: URL is not ready yet. Please retry later.");
        }
    }

    public ConcurrentHashMap<String, String> getViewerUrlMap() {
        return viewerUrlMap;
    }

    private void startCloudflared(String clientId, String clientViewerPath) {
        new Thread(() -> {
            try {
                ProcessBuilder cloudflared = new ProcessBuilder(
                        cloudflaredPath,
                        "tunnel",
                        "--url", "http://localhost:8080/viewer/" + clientId,
                        "--hostname", "viewer-" + clientId + ".trycloudflare.com"
                );
                cloudflared.redirectErrorStream(true);

                Process cloudProcess = cloudflared.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(cloudProcess.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("Cloudflared log line: " + line);

                    if (line.contains("trycloudflare.com") && line.contains("http")) {
                        int startIndex = line.indexOf("http");
                        int endIndex = line.indexOf(" ", startIndex); // 공백으로 URL 끝을 찾음
                        endIndex = (endIndex == -1) ? line.length() : endIndex;
                        String url = line.substring(startIndex, endIndex).trim();

                        if (url.startsWith("http") && url.contains("trycloudflare.com")) {
                            viewerUrlMap.put(clientId, url);
                            break;
                        }
                    }
                }

                int exitCode = cloudProcess.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("Cloudflared process exited with code: " + exitCode);
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Cloudflared process failed", e);
            }
        }).start();
    }

    public static class UploadResponse {
        private final String clientId;
        private final String viewerUrl;
        private final String outputUUID; // outputUUID 필드 추가

        public UploadResponse(String clientId, String viewerUrl, String outputUUID) {
            this.clientId = clientId;
            this.viewerUrl = viewerUrl;
            this.outputUUID = outputUUID;
        }

        public String getClientId() {
            return clientId;
        }

        public String getViewerUrl() {
            return viewerUrl;
        }

        public String getOutputUUID() {
            return outputUUID; // Getter 추가
        }
    }

}

