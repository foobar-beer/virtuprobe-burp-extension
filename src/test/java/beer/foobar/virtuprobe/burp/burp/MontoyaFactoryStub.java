package beer.foobar.virtuprobe.burp.burp;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Makes Montoya's static factories usable in a unit test.
 *
 * <p>{@code HttpRequest.httpRequest(...)} and its siblings delegate to
 * {@code ObjectFactoryLocator.FACTORY}, which only Burp populates, so outside Burp every one of them
 * throws a NullPointerException. That matters more than it sounds: {@link CommandApplier} deliberately
 * catches everything so a bad command cannot kill the poll loop, so an unpopulated factory turns into
 * a tidy "could not apply" and the test passes for a reason that has nothing to do with the code. Four
 * cases in this suite were green that way before this stub existed, including the one asserting that an
 * unknown destination sends nothing, which is true when NOTHING can be sent at all.
 *
 * <p>So the factory is filled in rather than the applier reshaped: the routing is what is worth
 * testing, and it should be tested as the code actually runs.
 */
final class MontoyaFactoryStub {

    private MontoyaFactoryStub() {
    }

    /** Installs the stub. Idempotent, and left in place: the field is global to the JVM. */
    static void install() {
        if (ObjectFactoryLocator.FACTORY != null) {
            return;
        }
        final MontoyaObjectFactory factory = mock(MontoyaObjectFactory.class);

        // Deep stubs so httpService().host() and friends answer without stubbing every getter, but the
        // three values the applier passes through are stubbed for real, because a test that cannot see
        // the target reach the request is not testing the target reaching the request.
        when(factory.httpService(anyString(), anyInt(), anyBoolean())).thenAnswer(call -> {
            HttpService service = mock(HttpService.class);
            when(service.host()).thenReturn(call.getArgument(0));
            when(service.port()).thenReturn(call.getArgument(1));
            when(service.secure()).thenReturn(call.getArgument(2));
            return service;
        });
        when(factory.byteArray(any(byte[].class))).thenAnswer(call -> mock(ByteArray.class));
        when(factory.httpRequest(any(HttpService.class), any(ByteArray.class))).thenAnswer(call -> {
            HttpRequest request = mock(HttpRequest.class);
            when(request.httpService()).thenReturn(call.getArgument(0));
            return request;
        });
        when(factory.httpResponse()).thenAnswer(call -> mock(HttpResponse.class));
        when(factory.httpRequestResponse(any(HttpRequest.class), any(HttpResponse.class)))
                .thenAnswer(call -> {
                    HttpRequestResponse pair = mock(HttpRequestResponse.class, RETURNS_DEEP_STUBS);
                    when(pair.request()).thenReturn(call.getArgument(0));
                    when(pair.response()).thenReturn(call.getArgument(1));
                    return pair;
                });

        ObjectFactoryLocator.FACTORY = factory;
    }
}
