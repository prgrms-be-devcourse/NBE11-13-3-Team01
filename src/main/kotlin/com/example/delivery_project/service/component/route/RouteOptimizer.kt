package com.example.delivery_project.service.component.route

interface RouteOptimizer {
    fun optimize(context: RouteOptimizationContext): OptimizedRoute
}
