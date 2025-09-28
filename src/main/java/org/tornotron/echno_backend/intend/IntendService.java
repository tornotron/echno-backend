package org.tornotron.echno_backend.intend;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.DtoConversions.IntendDtoConvertor;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.intend.dto.IntendCreationDto;
import org.tornotron.echno_backend.intend.dto.IntendDto;
import org.tornotron.echno_backend.intend.enums.IntendStatus;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

import java.util.List;

@Service
public class IntendService {

    private final IntendRepository intendRepository;
    private final UserRepository userRepository;

    public IntendService(IntendRepository intendRepository, UserRepository userRepository) {
        this.intendRepository = intendRepository;
        this.userRepository = userRepository;
    }


    @Transactional
    public IntendDto addIntend(IntendCreationDto intendCreationDto) {
        Intend intend = new Intend();
        User user = userRepository.findUserByName(intendCreationDto.getCreatedBy())
                        .orElseThrow(() -> new ResourceNotFoundException("User not found with name: " + intendCreationDto.getCreatedBy()));
        intend.setCreatedBy(user);
        intend.setIntendNumber(intendCreationDto.getIntendNumber());
        intend.setStatus(IntendStatus.valueOf(intendCreationDto.getStatus()));
        intend.setExpectedOn(intendCreationDto.getExpectedOn());
        intend.setRemarks(intendCreationDto.getRemarks());
        return IntendDtoConvertor.convertIntendToDto(intendRepository.save(intend));
    }


    @Transactional(readOnly = true)
    public Page<IntendDto> getAllIntends(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo,pageSize, Sort.by(Sort.Direction.ASC,"id"));
        return intendRepository.findAll(pageable)
                .map(IntendDtoConvertor::convertIntendToDto);
    }


    @Transactional(readOnly = true)
    public List<IntendDto> getAllIntends() {
        return intendRepository.findAll().stream()
                .map(IntendDtoConvertor::convertIntendToDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public IntendDto getAnIntend(Long id) {
        return intendRepository.findById(id)
                .map(IntendDtoConvertor::convertIntendToDto)
                .orElseThrow(() -> new ResourceNotFoundException("Intend not found with id: " + id));
    }
}
