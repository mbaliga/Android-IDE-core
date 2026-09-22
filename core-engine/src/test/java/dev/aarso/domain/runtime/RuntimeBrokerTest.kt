package dev.aarso.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBrokerTest {

    @Test
    fun prefersReadyOnDeviceRuntimeWhenCapabilitiesMatch() {
        val broker = RuntimeBroker {
            listOf(
                RuntimeProviderProfile(
                    providerId = "remote",
                    executionTargetType = dev.aarso.contracts.execution.ExecutionTargetType.SSH_HOST,
                    kinds = setOf(RuntimeKind.REMOTE_RUNNER),
                    capabilities = setOf(RuntimeCapability.JVM, RuntimeCapability.GRADLE),
                    locality = RuntimeLocality.USER_OWNED_REMOTE,
                    availability = RuntimeAvailability.READY,
                ),
                RuntimeProviderProfile(
                    providerId = "local",
                    executionTargetType = dev.aarso.contracts.execution.ExecutionTargetType.LOCAL_ANDROID,
                    kinds = setOf(RuntimeKind.LINUX_USERSPACE, RuntimeKind.JVM),
                    capabilities = setOf(RuntimeCapability.JVM, RuntimeCapability.GRADLE),
                    locality = RuntimeLocality.ON_DEVICE,
                    availability = RuntimeAvailability.READY,
                ),
            )
        }

        val result = broker.resolve(
            RuntimeRequest(
                requiredCapabilities = setOf(RuntimeCapability.JVM, RuntimeCapability.GRADLE),
                preferredKinds = listOf(RuntimeKind.JVM),
            )
        )

        val matched = result as RuntimeResolution.Matched
        assertEquals("local", matched.profile.providerId)
    }

    @Test
    fun neverSelectsNeedsSetupRuntime() {
        val broker = RuntimeBroker {
            RuntimeCatalog.baseline(
                hasTrustedSshHost = false,
                hasCiHost = false,
                localLinuxCapsuleReady = false,
            )
        }

        val result = broker.resolve(
            RuntimeRequest(requiredCapabilities = setOf(RuntimeCapability.JVM))
        )

        assertTrue(result is RuntimeResolution.NoMatch)
        val rejected = (result as RuntimeResolution.NoMatch).rejected.first { it.providerId == "linux-capsule" }
        assertTrue(RuntimeBlocker.NOT_READY in rejected.blockers)
    }

    @Test
    fun isolationRequirementRejectsPlainLinuxCapsule() {
        val broker = RuntimeBroker {
            RuntimeCatalog.baseline(
                hasTrustedSshHost = false,
                hasCiHost = false,
                localLinuxCapsuleReady = true,
                isolatedVmReady = false,
            )
        }

        val result = broker.resolve(
            RuntimeRequest(
                requiredCapabilities = setOf(RuntimeCapability.SHELL),
                requireIsolation = true,
            )
        )

        assertTrue(result is RuntimeResolution.NoMatch)
        val linux = (result as RuntimeResolution.NoMatch).rejected.first { it.providerId == "linux-capsule" }
        assertTrue(RuntimeBlocker.ISOLATION_REQUIRED in linux.blockers)
    }

    @Test
    fun remoteCanBeExplicitlyDisallowed() {
        val broker = RuntimeBroker {
            listOf(
                RuntimeProviderProfile(
                    providerId = "ssh",
                    executionTargetType = dev.aarso.contracts.execution.ExecutionTargetType.SSH_HOST,
                    kinds = setOf(RuntimeKind.REMOTE_RUNNER),
                    capabilities = setOf(RuntimeCapability.SHELL),
                    locality = RuntimeLocality.USER_OWNED_REMOTE,
                    availability = RuntimeAvailability.READY,
                )
            )
        }

        val result = broker.resolve(
            RuntimeRequest(
                requiredCapabilities = setOf(RuntimeCapability.SHELL),
                allowRemote = false,
            )
        )

        assertTrue(result is RuntimeResolution.NoMatch)
        assertTrue(RuntimeBlocker.REMOTE_DISALLOWED in (result as RuntimeResolution.NoMatch).rejected.single().blockers)
    }
}
