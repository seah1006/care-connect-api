package io.github.seah1006.careconnect.participant.web;

import io.github.seah1006.careconnect.participant.application.ParticipantService;
import io.github.seah1006.careconnect.participant.application.ParticipantView;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/participants")
public class ParticipantController {
    private final ParticipantService service;

    public ParticipantController(ParticipantService service) { this.service = service; }

    @GetMapping("/{id}")
    public ParticipantView get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping
    public ParticipantService.Page list(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) { return service.list(cursor, size); }
}
