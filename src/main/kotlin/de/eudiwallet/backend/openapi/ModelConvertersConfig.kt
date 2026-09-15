package de.eudiwallet.backend.openapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyName
import com.fasterxml.jackson.databind.introspect.Annotated
import com.fasterxml.jackson.databind.introspect.AnnotatedField
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.jackson.ModelResolver
import kotlinx.serialization.SerialName
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

@Configuration
class ModelConvertersConfig {
    @Bean
    fun registerSerialNameModelResolver(): ModelResolver {
        val swaggerMapper = ObjectMapper().findAndRegisterModules()

        val introspectorPair =
            AnnotationIntrospectorPair(
                KotlinxSerialNameIntrospector(),
                swaggerMapper.serializationConfig.annotationIntrospector,
            )
        return ModelResolver(swaggerMapper.setAnnotationIntrospector(introspectorPair))
    }

    @Bean
    fun modelConverters(modelResolver: ModelResolver?): ModelConverters {
        val converters = ModelConverters()
        converters.addConverter(modelResolver)
        return converters
    }
}

class KotlinxSerialNameIntrospector : NopAnnotationIntrospector() {
    override fun findNameForSerialization(a: Annotated): PropertyName? = findSerialName(a)

    override fun findNameForDeserialization(a: Annotated): PropertyName? = findSerialName(a)

    private fun findSerialName(a: Annotated): PropertyName? {
        val property =
            (a as? AnnotatedField)?.declaringClass?.kotlin?.memberProperties
                ?.filterIsInstance<kotlin.reflect.KProperty1<Any, *>>()
                ?.firstOrNull { it.name == a.name }

        return property?.findAnnotation<SerialName>()?.value?.let { PropertyName(it) }
    }
}
