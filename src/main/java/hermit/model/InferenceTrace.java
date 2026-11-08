package hermit.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Opt-in benchmark telemetry. Never records authorization headers, prompts or response text. */
public final class InferenceTrace {
    private static final ObjectMapper JSON=new ObjectMapper();
    private InferenceTrace(){}
    public static synchronized void record(String endpoint,String model,long startNanos,HttpResponse<String> response){
        if(System.getProperty("hermit.inference.trace")==null)return;
        try {
            record(endpoint,model,startNanos,response.statusCode(),JSON.readTree(response.body()).path("usage"));
        }catch(Exception e){
            System.err.println("[InferenceTrace] Could not record telemetry: "+e.getClass().getSimpleName());
        }
    }
    /** A call made without an HTTP response of its own (the Codex CLI): its status and token usage. */
    public static synchronized void record(String endpoint,String model,long startNanos,int status,JsonNode usage){
        String file=System.getProperty("hermit.inference.trace");if(file==null)return;
        try {
            var row=new LinkedHashMap<String,Object>();
            row.put("time",Instant.now().toString());row.put("endpoint",endpoint);row.put("model",model);
            row.put("elapsedMs",(System.nanoTime()-startNanos)/1_000_000);row.put("status",status);
            row.put("usage",usage);
            Path p=Path.of(file);Files.createDirectories(p.toAbsolutePath().getParent());
            Files.writeString(p,JSON.writeValueAsString(row)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(Exception e){
            System.err.println("[InferenceTrace] Could not record telemetry: "+e.getClass().getSimpleName());
        }
    }
}
