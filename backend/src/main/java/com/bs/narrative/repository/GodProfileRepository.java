package com.bs.narrative.repository;

import com.bs.narrative.domain.GodProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GodProfileRepository extends JpaRepository<GodProfile, String> {

    /** 批量查在场神明（FR-B2.1 一次性加载）。 */
    List<GodProfile> findByGodIdIn(List<String> godIds);
}
