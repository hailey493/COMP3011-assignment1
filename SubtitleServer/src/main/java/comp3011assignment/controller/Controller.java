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
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@RestController
@RequestMapping("/api/v1")
public class Controller {
	
	private final ConfigurableApplicationContext applicationContext;
	
	private final HttpClient healthClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2))
			.build();

	//Defines the response body
	public record ReadinessResponse(boolean ready) {}

	//get the ApplicationContext
	public Controller(ConfigurableApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}
	
	// Record server start time so up time can be calculated
	final Instant serverStart = Instant.now();
	
	// check graceful shutdown state and prevent multiple concurrent shutdown requests
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
	
	// the record for graceful shut down
	public record ShutdownResponse(
			String message) {}
	
    public record GlobalUsageResponse(double seconds) {}

    // Store milliseconds atomically so fractional seconds are preserved.
    // The total covers successful transcriptions since server startup.
    private final AtomicLong totalTranscriptMilliseconds = new AtomicLong(0);

	@PostMapping(value = "/subtitles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAudio(@RequestParam("audio") MultipartFile file) 
			throws IOException{
		
		// Testing with JMeter
		// The 2s delay are used to simulate a request so 250 concurrent request can be tested without using OpenAI API
//		try {
//			Thread.sleep(2000);
//		} catch (InterruptedException e) {
//			Thread.currentThread().interrupt();
//		}
//		return ResponseEntity.ok().body("test transcription");
		
		//Handle error if file is empty
		if (file.isEmpty()) {
		    Instant now = Instant.now();

		    ErrorResponse errorResponse = new ErrorResponse(
		        now,
		        400,
		        "Bad Request",
		        "The audio file is empty",
		        "/api/v1/subtitles"
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
                    .model("whisper-1")
                    .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                    .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.WORD)
                    .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.SEGMENT)
                    .build();
    
        	// Execute the request and get response
            var result = client
                    .audio()
                    .transcriptions()
                    .create(params);
            
			// Handle empty reponse
			if (result.isDiarized()) {
				var emptyResult = result.asDiarized();

				if (emptyResult.text().isBlank()
						&& emptyResult.segments().isEmpty()) {

					return ResponseEntity.ok(java.util.Map.of(
							"text", "",
							"segments", java.util.List.of()
					));
				}
			}

            var transcription = result.asVerbose();
            transcription.usage().ifPresent(usage ->
                    totalTranscriptMilliseconds.addAndGet(
                            Math.round(usage.seconds() * 1_000.0)));
            
            return ResponseEntity.ok(transcription);
        	
        	
        } catch (Exception e) {
        	e.printStackTrace();
        	Instant now = Instant.now();
        	
        	ErrorResponse errorResponse = new ErrorResponse(
        	        now,
        	        500,
        	        "Internal Server Error",
        	        "Failed to transcribe audio file.",
        	        "/api/v1/subtitles"
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
			// Only the first shutdown request is accepted
			if (shutdownStatus.compareAndSet(false, true)) {
				ShutdownResponse message = new ShutdownResponse("Graceful shutdown requested.");
				new Thread(() -> applicationContext.close()).start();
				return ResponseEntity.accepted().body(message);
			}
			// other shutdown request will get 409
			else {
				ErrorResponse response = new ErrorResponse(now,409,"Conflict","Graceful shutdown is already in progress.","/api/v1/admin/shutdown");
				return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
			}
		}catch(Exception e) {
			ErrorResponse errorResponse = new ErrorResponse(now,500,"Internal Server Error","An unexpected server error occurred.","/api/v1/admin/shutdown");
			return ResponseEntity.internalServerError().body(errorResponse);
		}
	}
	
    @GetMapping("/global/usage")
    public ResponseEntity<GlobalUsageResponse> usageStats() {
        return ResponseEntity.ok(new GlobalUsageResponse(
                totalTranscriptMilliseconds.get() / 1_000.0));
    }

	@GetMapping("/subtitles/health")
	public ResponseEntity<ReadinessResponse> checkSubtitleReadiness() {
		String apiKey = System.getenv("OPENAI_API_KEY");

		if (apiKey == null || apiKey.isBlank()) {
			System.err.println("Health check: OPENAI_API_KEY is missing.");
			return readinessResponse(false);
		}

		String baseUrl = System.getenv("OPENAI_BASE_URL");

		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = "https://api.openai.com/v1";
		}

		try {
			// build request
			HttpRequest request = HttpRequest.newBuilder(
					URI.create(
							baseUrl.replaceAll("/+$", "")
							+ "/models/whisper-1"
					))
					.timeout(Duration.ofSeconds(2))
					.header("Authorization", "Bearer " + apiKey)
					.GET()
					.build();

			//Send request
			healthClient.send(
					request,
					HttpResponse.BodyHandlers.discarding()
			);

			return readinessResponse(true);

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return readinessResponse(false);

		} catch (IOException | IllegalArgumentException e) {
			System.err.println(
					"Provider connection check failed: "
					+ e.getClass().getSimpleName()
					+ ": " + e.getMessage()
			);

			return readinessResponse(false);
		}
	}

	private ResponseEntity<ReadinessResponse> readinessResponse(
			boolean ready) {

		return ResponseEntity.status(ready ? 200 : 503)
				.header("Cache-Control", "no-store")
				.body(new ReadinessResponse(ready));
}
}
