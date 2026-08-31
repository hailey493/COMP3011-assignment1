package comp3011assignment.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


@RestController
@RequestMapping("/api/v1")
public class Controller {
	
	@PostMapping(value = "/audio/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String uploadAudio(@RequestParam("audio") MultipartFile file) 
			throws IOException{
        if (file.isEmpty()) {
        	String emptyFile = "Please upload a valid audio file.";
            return emptyFile;
        }
        Path tempFile = null;
        try {
        	OpenAIClient client = OpenAIOkHttpClient.fromEnv();
            tempFile = Files.createTempFile("audio-","-" + file.getOriginalFilename());
            
        	file.transferTo(tempFile);
        	
        	var params = TranscriptionCreateParams.builder()
                    .file(tempFile)
                    .model("gpt-4o-mini-transcribe")
                    .build();

            var result = client
                    .audio()
                    .transcriptions()
                    .create(params);
            
            return result.asTranscription().text();
        } catch (Exception e) {
        	System.out.println("OpenAI error type: " + e.getClass().getName());
            System.out.println("OpenAI error message: " + e.getMessage());
            
            String failUpload = "Failed to upload file";
            return failUpload;
        } 
        finally {
        	if (tempFile != null) {
        		Files.deleteIfExists(tempFile);
        	}
        }
    }
	
}