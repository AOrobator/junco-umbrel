package com.junco.adapter

import io.ktor.http.HttpStatusCode

class ApiException(val status: HttpStatusCode, message: String) : RuntimeException(message) {
    companion object {
        fun badRequest(message: String) = ApiException(HttpStatusCode.BadRequest, message)
        fun unauthorized(message: String) = ApiException(HttpStatusCode.Unauthorized, message)
        fun forbidden(message: String) = ApiException(HttpStatusCode.Forbidden, message)
        fun notFound(message: String) = ApiException(HttpStatusCode.NotFound, message)
        fun internal(message: String) = ApiException(HttpStatusCode.InternalServerError, message)
    }
}
