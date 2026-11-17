package com.growercare.grower.adapters.in.web;

import com.growercare.grower.adapters.in.web.GrowerDtos.GrowerReplicaView;
import com.growercare.grower.application.GrowerQueries;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** SPEC-GRW-01 R42: service-to-service reads (gc-security admits only allow-listed clients on /internal/**). */
@RestController
class InternalGrowerController {

    private final GrowerQueries queries;

    InternalGrowerController(GrowerQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/internal/growers/{growerId}")
    GrowerReplicaView replica(@PathVariable String growerId) {
        return GrowerReplicaView.of(queries.replica(GrowerDtos.growerIdOrNotFound(growerId)));
    }
}
