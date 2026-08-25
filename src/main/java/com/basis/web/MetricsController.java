package com.basis.web;

import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The counters, on a page, because a metric nobody reads is a metric nobody acts on.
 *
 * <p>The project's claim is a number of real errors found in real accounts. This is where that
 * number comes from, and where the parse failures are: those are not just a count, they are the
 * bug backlog in priority order, since a reason that keeps appearing is a broker format basis
 * gets wrong for everybody who has that broker.
 *
 * <p>Real and demo are shown separately and never summed. A demo is seeded data proving the
 * classifier works; counting it toward errors found in real accounts would turn the one number
 * that matters into a number that flatters itself.
 *
 * <p>Nothing here identifies anybody. See {@link Usage} for what may be recorded, and
 * PRIVACY.md for the promise this has to keep.
 */
@Controller
@org.springframework.context.annotation.Profile("web")
public class MetricsController {

    private final Usage usage;
    private final SessionStore sessions;

    public MetricsController(Usage usage, SessionStore sessions) {
        this.usage = usage;
        this.sessions = sessions;
    }

    @GetMapping("/metrics")
    public String metrics(Model model) {
        Map<String, Long> all = usage.snapshot();
        model.addAttribute("real", all.entrySet().stream()
                .filter(e -> e.getKey().startsWith("real."))
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().substring("real.".length()), Map.Entry::getValue,
                        (a, b) -> a, java.util.TreeMap::new)));
        model.addAttribute("demo", all.entrySet().stream()
                .filter(e -> e.getKey().startsWith("demo."))
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().substring("demo.".length()), Map.Entry::getValue,
                        (a, b) -> a, java.util.TreeMap::new)));
        model.addAttribute("breaksFound", all.entrySet().stream()
                .filter(e -> e.getKey().startsWith("real.breaks.")
                        && !e.getKey().endsWith("confirmed")
                        && !e.getKey().endsWith("suspected")
                        && !e.getKey().endsWith("viewed"))
                .mapToLong(Map.Entry::getValue).sum());
        model.addAttribute("accounts", usage.count("real.uploads.parsed"));
        model.addAttribute("held", sessions.size());
        return "metrics";
    }
}
