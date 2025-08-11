package org.tornotron.echno_backend.user;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.user.dto.UserCreationDto;
import org.tornotron.echno_backend.user.dto.UserDto;
import org.tornotron.echno_backend.user.dto.UserPatchDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
@Validated
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserDto> createUser(@RequestBody @Valid UserCreationDto userCreationDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.addUser(userCreationDto));
    }

    @GetMapping
    public ResponseEntity<List<UserDto>> readAllUsers(@RequestParam(defaultValue = "0") int pageNo,
                                                      @RequestParam(defaultValue = "10") int pageSize) {
        Page<UserDto> users = userService.getAllUsers(pageNo,pageSize);
        return new ResponseEntity<>(users.getContent(), HttpStatus.OK);
    }

    @PatchMapping("{id}")
    public ResponseEntity<UserDto> partialUpdateAUser(@RequestBody Map<String, Object> updates, @PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.OK).body(userService.partialUpdateAnUser(updates, id));
    }

    @PatchMapping("/batch")
    public ResponseEntity<ApiResponse> batchUpdateUsers(@Valid @RequestBody List<UserPatchDto> updates) {
        userService.batchUpdateUser(updates);
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Batch update successful"));
    }

}
