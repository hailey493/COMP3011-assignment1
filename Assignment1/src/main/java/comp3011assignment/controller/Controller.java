package comp3011assignment.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;


@RestController
@RequestMapping("/api/v1")
public class Controller {
	
	@PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadAudio(@RequestParam("audio") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Please upload a valid audio file.");
        }

        try {
            
        	System.out.println("Audio received by backend"); // testing to see if the audio is received
            return ResponseEntity.ok("Audio received");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to upload file.");
        }
    }
}