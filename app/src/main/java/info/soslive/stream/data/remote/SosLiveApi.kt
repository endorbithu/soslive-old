package info.soslive.stream.data.remote

import info.soslive.stream.data.remote.dto.AddCommentRequest
import info.soslive.stream.data.remote.dto.CommentDto
import info.soslive.stream.data.remote.dto.CommentListDto
import info.soslive.stream.data.remote.dto.CreateEventRequest
import info.soslive.stream.data.remote.dto.EventDto
import info.soslive.stream.data.remote.dto.EventListDto
import info.soslive.stream.data.remote.dto.FacebookLoginRequest
import info.soslive.stream.data.remote.dto.GoogleLoginRequest
import info.soslive.stream.data.remote.dto.LocationUpdateRequest
import info.soslive.stream.data.remote.dto.LoginRequest
import info.soslive.stream.data.remote.dto.LogoutRequest
import info.soslive.stream.data.remote.dto.PhotoDto
import info.soslive.stream.data.remote.dto.PhotoListDto
import info.soslive.stream.data.remote.dto.RefreshRequest
import info.soslive.stream.data.remote.dto.RegisterRequest
import info.soslive.stream.data.remote.dto.SessionDto
import info.soslive.stream.data.remote.dto.UpdateMeRequest
import info.soslive.stream.data.remote.dto.UserDto
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** Unauthenticated endpoints. Uses an OkHttp client without the auth interceptor/authenticator. */
interface AuthApi {
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): SessionDto

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): SessionDto

    @POST("auth/google")
    suspend fun google(@Body body: GoogleLoginRequest): SessionDto

    @POST("auth/facebook")
    suspend fun facebook(@Body body: FacebookLoginRequest): SessionDto

    /** Blocking on purpose: called from OkHttp's [okhttp3.Authenticator] on a network thread. */
    @POST("auth/refresh")
    fun refresh(@Body body: RefreshRequest): Call<SessionDto>
}

/** Endpoints that need "Authorization: Bearer <accessToken>". */
interface SosLiveApi {
    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

    @GET("me")
    suspend fun me(): UserDto

    @PATCH("me")
    suspend fun updateMe(@Body body: UpdateMeRequest): UserDto

    @POST("events")
    suspend fun createEvent(@Body body: CreateEventRequest): EventDto

    @GET("events")
    suspend fun events(): EventListDto

    @GET("events/{id}")
    suspend fun event(@Path("id") id: Long): EventDto

    @POST("events/{id}/location")
    suspend fun updateLocation(@Path("id") id: Long, @Body body: LocationUpdateRequest): EventDto

    @POST("events/{id}/stop")
    suspend fun stopEvent(@Path("id") id: Long): EventDto

    @GET("events/{id}/comments")
    suspend fun comments(@Path("id") id: Long, @Query("sinceId") sinceId: Long? = null): CommentListDto

    @POST("events/{id}/comments")
    suspend fun addComment(@Path("id") id: Long, @Body body: AddCommentRequest): CommentDto

    @GET("events/{id}/photos")
    suspend fun photos(@Path("id") id: Long): PhotoListDto

    @Multipart
    @POST("events/{id}/photos")
    suspend fun uploadPhoto(@Path("id") id: Long, @Part photo: MultipartBody.Part): PhotoDto
}
