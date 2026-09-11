package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.delivery.RiskFactor
import org.springframework.data.jpa.repository.JpaRepository

interface RiskFactorRepository : JpaRepository<RiskFactor, Long>
