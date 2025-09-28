package org.tornotron.echno_backend.intend;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.intend.dto.IntendCreationDto;
import org.tornotron.echno_backend.intend.dto.IntendDto;

import java.util.List;

@RestController
@RequestMapping("/api/v1/intends")
@Validated
public class IntendController {

    private final IntendService intendService;

    public IntendController(IntendService intendService) {
        this.intendService = intendService;
    }

    @PostMapping
    public ResponseEntity<IntendDto> createIntend(@Valid @RequestBody IntendCreationDto intendCreationDto) {
        return new ResponseEntity<>(intendService.addIntend(intendCreationDto), HttpStatus.CREATED);
    }

    @GetMapping("/all")
    public ResponseEntity<Page<IntendDto>> getAllIntends(
            @RequestParam(defaultValue = "0") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize
    ) {
        return new ResponseEntity<>(intendService.getAllIntends(pageNo, pageSize), HttpStatus.OK);
    }

    @GetMapping
    public ResponseEntity<List<IntendDto>> getAllIntends() {
        return new ResponseEntity<>(intendService.getAllIntends(), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<IntendDto> getAnIntend(@PathVariable Long id) {
        return new ResponseEntity<>(intendService.getAnIntend(id), HttpStatus.OK);
    }
}
