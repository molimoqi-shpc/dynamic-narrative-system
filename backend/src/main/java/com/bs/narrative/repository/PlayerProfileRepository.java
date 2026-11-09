package com.bs.narrative.repository;

import com.bs.narrative.domain.PlayerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlayerProfileRepository extends JpaRepository<PlayerProfile, String> {

    Optional<PlayerProfile> findByUsername(String username);
}
