package hermit.model;

import java.net.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluation boundary: inference stays on the local network's machines, never a hosted fallback - except the hosted
 * endpoints a run names explicitly by host in {@code -Dhermit.inference.allowHosted}
 * (e.g. {@code api.example.com,inference.example.com}): a hosted-assisted run reaches exactly those,
 * and still no web search, issue tracker or other provider.
 */
public final class EndpointPolicy {
    private EndpointPolicy() {}
    public static boolean localOnly(){return Boolean.getBoolean("hermit.inference.localOnly");}

    /** The hosts named in -Dhermit.inference.allowHosted (lower-cased). */
    public static Set<String> allowedHosts() {
        String spec = System.getProperty("hermit.inference.allowHosted", "");
        return Arrays.stream(spec.split(",")).map(h -> h.strip().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public static boolean allowed(String url){
        if(!localOnly())return true;
        try {
            String host=URI.create(url).getHost();
            if(host==null)return false;
            if(allowedHosts().contains(host.toLowerCase(Locale.ROOT)))return true;
            // Avoid even resolving known hosted names during an isolated evaluation.
            if(!host.equals("localhost") && !host.endsWith(".local")
                    && !host.matches("[0-9a-fA-F:.]+") && !host.startsWith("["))return false;
            for(InetAddress a:InetAddress.getAllByName(host))
                if(!(a.isLoopbackAddress()||a.isSiteLocalAddress()||a.isLinkLocalAddress()))return false;
            return true;
        }catch(Exception e){return false;}
    }
    public static void requireAllowed(String url){
        if(!allowed(url))throw new IllegalStateException("Hosted inference disabled for local-only run");
    }
}
