package app.aaps.plugins.sync.smsCommunicator

import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.SyncStringsValues
import app.aaps.plugins.sync.smsCommunicator.otp.OneTimePassword
import app.aaps.plugins.sync.smsCommunicator.otp.OneTimePasswordValidationResult
import app.aaps.shared.tests.TestBase
import app.aaps.shared.tests.generatedTextResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AuthRequestTest : TestBase() {

    @Mock lateinit var smsCommunicator: SmsCommunicator
    private val rh = generatedTextResolver("sync" to SyncStringsValues::textOf)
    @Mock lateinit var otp: OneTimePassword
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var commandQueue: CommandQueue

    private var sentSms: Sms? = null
    private var actionCalled = false

    @BeforeEach fun prepareTests() {
        whenever(otp.isEnabled()).thenReturn(true)
        doAnswer { invocation: InvocationOnMock ->
            sentSms = invocation.getArgument(0)
            null
        }.whenever(smsCommunicator).sendSMS(anyOrNull())
    }

    private fun request() = AuthRequest(
        Sms("aNumber", "aText"), "Reply with ABC", "ABC",
        object : SmsAction(false) {
            override suspend fun run() { actionCalled = true }
        }, aapsLogger, smsCommunicator, rh, otp, dateUtil, commandQueue
    )

    @Test fun disabledAuthenticatorRequiresTheSmsChallenge() = runTest {
        whenever(otp.isEnabled()).thenReturn(false)
        val request = request()
        request.action("ABC")
        assertThat(actionCalled).isTrue()
        verify(otp, never()).checkOTP(anyOrNull())
        actionCalled = false
        request.action("ABC")
        assertThat(actionCalled).isFalse()
    }

    @Test fun disabledAuthenticatorRejectsWrongCodeAndCancelsRequest() = runTest {
        whenever(otp.isEnabled()).thenReturn(false)
        val request = request()
        request.action("XYZ")
        assertThat(actionCalled).isFalse()
        assertThat(sentSms!!.text).isEqualTo("Wrong code. Command cancelled.")
        request.action("ABC")
        assertThat(actionCalled).isFalse()
        verify(otp, never()).checkOTP(anyOrNull())
    }

    @Test fun disabledAuthenticatorDoesNotAcceptAnOtpInsteadOfTheSmsCode() = runTest {
        whenever(otp.isEnabled()).thenReturn(false)
        val request = request()
        request.action("123456789")
        assertThat(actionCalled).isFalse()
        verify(otp, never()).checkOTP(anyOrNull())
    }

    @Test fun enabledAuthenticatorDoesNotFallBackToTheSmsCode() = runTest {
        whenever(otp.checkOTP("ABC")).thenReturn(OneTimePasswordValidationResult.ERROR_WRONG_OTP)
        request().action("ABC")
        assertThat(actionCalled).isFalse()
        verify(otp).checkOTP("ABC")
    }

    @Test fun enabledAuthenticatorAcceptsAnOtpDifferentFromTheChallengeText() = runTest {
        whenever(otp.checkOTP("123456789")).thenReturn(OneTimePasswordValidationResult.OK)
        request().action("123456789")
        assertThat(actionCalled).isTrue()
    }

    @Test fun disabledAuthenticatorStillEnforcesConfirmationTimeout() = runTest {
        whenever(otp.isEnabled()).thenReturn(false)
        whenever(dateUtil.now()).thenReturn(10_000L)
        val request = request()
        whenever(dateUtil.now()).thenReturn(10_000L + Constants.SMS_CONFIRM_TIMEOUT)
        request.action("ABC")
        assertThat(actionCalled).isFalse()
    }

    @Test fun doTests() = runTest {
        val requester = Sms("aNumber", "aText")
        val action: SmsAction = object : SmsAction(false) {
            override suspend fun run() {
                actionCalled = true
            }
        }

        // Check if SMS requesting code is sent
        var authRequest = AuthRequest(requester, "Request text", "ABC", action, aapsLogger, smsCommunicator, rh, otp, dateUtil, commandQueue)
        assertThat(sentSms!!.phoneNumber).isEqualTo("aNumber")
        assertThat(sentSms!!.text).isEqualTo("Request text")

        // wrong reply
        actionCalled = false
        authRequest.action("EFG")
        assertThat(sentSms!!.phoneNumber).isEqualTo("aNumber")
        assertThat(sentSms!!.text).isEqualTo("Wrong code. Command cancelled.")
        assertThat(actionCalled).isFalse()

        // correct reply
        authRequest = AuthRequest(requester, "Request text", "ABC", action, aapsLogger, smsCommunicator, rh, otp, dateUtil, commandQueue)
        actionCalled = false
        whenever(otp.checkOTP(anyOrNull())).thenReturn(OneTimePasswordValidationResult.OK)
        authRequest.action("ABC")
        assertThat(actionCalled).isTrue()
        // second time action should not be called
        actionCalled = false
        authRequest.action("ABC")
        assertThat(actionCalled).isFalse()

        // test timed out message
        val now: Long = 10000
        whenever(dateUtil.now()).thenReturn(now)
        authRequest = AuthRequest(requester, "Request text", "ABC", action, aapsLogger, smsCommunicator, rh, otp, dateUtil, commandQueue)
        actionCalled = false
        // SMS_CONFIRM_TIMEOUT is already in milliseconds (5L * 60 * 1000); don't re-wrap in T.mins.
        whenever(dateUtil.now()).thenReturn(now + Constants.SMS_CONFIRM_TIMEOUT + 1)
        authRequest.action("ABC")
        assertThat(actionCalled).isFalse()
    }
}
