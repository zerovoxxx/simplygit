package com.example.simplygit.di

import com.example.simplygit.domain.service.NotificationPublisher
import com.example.simplygit.notification.NotificationPublisherImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds NotificationPublisher → NotificationPublisherImpl (SPEC §4.8 Iteration 2). */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationModule {

    @Binds
    @Singleton
    abstract fun bindNotificationPublisher(
        impl: NotificationPublisherImpl,
    ): NotificationPublisher
}
