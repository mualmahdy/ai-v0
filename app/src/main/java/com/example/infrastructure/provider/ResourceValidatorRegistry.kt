package com.example.infrastructure.provider

/**
 * ResourceValidatorRegistry — Phase 4
 * 
 * Registry of validators for different resource types.
 * Validates resource configurations before registration.
 */
class ResourceValidatorRegistry {
    private val validators = mutableMapOf<String, ResourceValidator>()
    
    fun register(resourceType: String, validator: ResourceValidator) {
        validators[resourceType] = validator
    }
    
    fun validate(resourceType: String, config: Map<String, Any>): ValidationResult {
        val validator = validators[resourceType]
        return if (validator != null) {
            validator.validate(config)
        } else {
            ValidationResult(isValid = true, errors = emptyList())
        }
    }
    
    interface ResourceValidator {
        fun validate(config: Map<String, Any>): ValidationResult
    }
    
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList()
    )
}

fun defaultResourceValidatorRegistry(): ResourceValidatorRegistry {
    return ResourceValidatorRegistry()
}
