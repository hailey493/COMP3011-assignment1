package comp3011assignment.controller;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


@RestController
@RequestMapping("/api/v1")
public class Controller {
	
	private final ConfigurableApplicationContext applicationContext;
	
	//get the ApplicationContext
	public Controller(ConfigurableApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}
	
	// get the instant time when server start 
	final Instant serverStart = Instant.now();
	
	// check graceful shutdown state 
	final AtomicBoolean  shutdownStatus = new AtomicBoolean(false);
	
	// the record for uptime response
	public record UptimeResponse(
			Instant utcServerStart,
			Instant utcNow,
			Double serverUptimeSeconds
			) {}
	
	// the record for conflict 
	public record ErrorResponse(
			Instant timestamp,
			Integer status,
			String error,
			String message,
			String path) {}
	
	// the record for graceful shutdodwn
	public record ShutdownResponse(
			String message) {}
	
	// the record for token usage response
	public record GlobalStatsResponse(
			Long inputTokens,
			Long outputTokens) {}
	
	private final AtomicLong inputToken = new AtomicLong(0);
	private final AtomicLong outputToken = new AtomicLong(0);
	
	@PostMapping(value = "/audio/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAudio(@RequestParam("audio") MultipartFile file) 
			throws IOException{
		//Handle error if file is empty
		if (file.isEmpty()) {
		    Instant now = Instant.now();

		    ErrorResponse errorResponse = new ErrorResponse(
		        now,
		        400,
		        "Bad Request",
		        "The audio file is empty",
		        "/api/v1/audio/transcribe"
		    );

		    return ResponseEntity.badRequest().body(errorResponse);
		}
		
        Path tempFile = null;
        try {
        	// Start the client using environment variables
        	OpenAIClient client = OpenAIOkHttpClient.fromEnv();
            tempFile = Files.createTempFile(
            		"audio-",
            		"-" + file.getOriginalFilename()
            );
            
        	file.transferTo(tempFile);
        	
        	// Build the parameter for the request
        	var params = TranscriptionCreateParams.builder()
                    .file(tempFile)
                    .model("gpt-4o-mini-transcribe")
                    .build();

        	// Execute the request and get response
            var result = client
                    .audio()
                    .transcriptions()
                    .create(params);
            
            // Update token statistics
            result.asTranscription().usage().ifPresent(usage -> {
            	
            	usage.tokens().ifPresent(tokens ->{
            		
            		inputToken.addAndGet(tokens.inputTokens());
            		outputToken.addAndGet(tokens.outputTokens());
            	});
            });
        	
            return ResponseEntity.ok(result.asTranscription().text());
        } catch (Exception e) {
        	Instant now = Instant.now();
        	
        	ErrorResponse errorResponse = new ErrorResponse(
        	        now,
        	        500,
        	        "Internal Server Error",
        	        "Failed to transcribe audio file.",
        	        "/api/v1/audio/transcribe"
        	    );
        	    return ResponseEntity.internalServerError().body(errorResponse);
        }
        finally {
        	if (tempFile != null) {
        		Files.deleteIfExists(tempFile);
        	}
        }
    }
	@GetMapping("/admin/uptime")
	public ResponseEntity<?> getServerUptime(){
		Instant now = Instant.now();
		try {
			Duration elapsed = Duration.between(serverStart, now);
			Double secondsPassed = elapsed.getSeconds() + (elapsed.getNano() / 1_000_000_000.0) ;
			UptimeResponse response = new UptimeResponse(serverStart, now, secondsPassed);
			
			return ResponseEntity.ok(response);
		}catch (Exception e) {
			ErrorResponse errorResponse = new ErrorResponse(
					now,500,
					"Internal Server Error",
					"An unexpected server error occurred.",
					"/api/v1/admin/uptime");
			return ResponseEntity.internalServerError().body(errorResponse);
		}
	}
	
	@PostMapping("/admin/shutdown")
	public ResponseEntity<?> shutdownServer(){
		Instant now = Instant.now();
		try {
			if (shutdownStatus.compareAndSet(false, true)) {
				ShutdownResponse message = new ShutdownResponse("Graceful shutdown requested.");
				new Thread(() -> applicationContext.close()).start();
				return ResponseEntity.accepted().body(message);
			}
			else {
				ErrorResponse response = new ErrorResponse(now,409,"Conflict","Graceful shutdown is already in progress.","/api/v1/admin/shutdown");
				return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
			}
		}catch(Exception e) {
			ErrorResponse errorResponse = new ErrorResponse(now,500,"Internal Server Error","An unexpected server error occurred.","/api/v1/admin/shutdown");
			return ResponseEntity.internalServerError().body(errorResponse);
		}
	}
	
	@GetMapping("/global/stats")
	public ResponseEntity<?> usageStats(){
		try {
			GlobalStatsResponse globalStats = 
					new GlobalStatsResponse(
							inputToken.get(),
							outputToken.get()
					);
			
			return ResponseEntity.ok(globalStats);
		}catch(Exception e) {
			Instant now = Instant.now();
			ErrorResponse errorResponse = new ErrorResponse(now,500,"Internal Server Error","An unexpected server error occurred.","/api/v1/global/stats");
			return ResponseEntity.internalServerError().body(errorResponse);
		}
	}
	
}