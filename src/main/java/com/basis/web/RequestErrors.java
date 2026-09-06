package com.basis.web;

import com.basis.importer.BrokerProfiles;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * The failures that happen before a controller method is entered.
 *
 * <p>basis speaks HTML and nothing else: every route in {@code docs/openapi.json} answers
 * {@code text/html}, {@code text/csv} or {@code text/plain}, and there is no JSON surface
 * anywhere in the app. Spring's default error handling does not know that. A request that
 * cannot be bound to a handler signature never reaches
 * {@link UploadController}'s {@code @ExceptionHandler} methods, so it fell through to the
 * framework and came back as {@code {"timestamp":...,"status":400,"error":"Bad Request"}} -
 * a content type the contract does not declare on any operation, and a body a browser
 * renders as raw text to somebody who has just been asked to upload their trading history.
 *
 * <p>A malformed multipart stream was worse: the multipart resolver throws before dispatch
 * picks a handler at all, so nothing resolved it, Tomcat logged a stack trace and the caller
 * got 500 with the same JSON. Schemathesis found that one by generating a body whose closing
 * boundary is missing - "Stream ended unexpectedly" - which is not a request anybody writes
 * by hand, and it is a server error on a public unauthenticated endpoint.
 *
 * <p>{@code @ControllerAdvice} rather than only methods on the controller, because that is
 * the level at which Spring consults handlers when the failing request has no handler method
 * yet. It is not sufficient on its own: a controller's own {@code @ExceptionHandler} is
 * consulted first, so {@link UploadController}'s {@code RuntimeException} catch-all claimed
 * every {@code MultipartException} that did reach dispatch and answered 500 for a request
 * that simply was not multipart. The controller therefore declares the two specific types as
 * well, and both routes render through {@link #page} so a person sees one behaviour and not
 * two.
 */
@ControllerAdvice
@org.springframework.context.annotation.Profile("web")
class RequestErrors {

    static final String UNREADABLE = "basis could not read that upload.";
    static final String UNREADABLE_NEXT =
            "The file did not arrive intact. Choose it again and resubmit the form.";
    static final String INCOMPLETE = "basis did not receive everything that request needed.";
    static final String INCOMPLETE_NEXT =
            "Choose a transaction history file and submit the form again.";

    private final WebConfig.Limits limits;

    RequestErrors(WebConfig.Limits limits) {
        this.limits = limits;
    }

    /** A body that is not a readable multipart request, including one that stops early. */
    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String unreadableUpload(Model model) {
        return page(model, UNREADABLE, UNREADABLE_NEXT, limits);
    }

    /** The form posted without the file, or without one of the fields a route needs. */
    @ExceptionHandler({MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String somethingWasNotSent(Model model) {
        return page(model, INCOMPLETE, INCOMPLETE_NEXT, limits);
    }

    /** The upload form again, with a sentence saying what to change. */
    static String page(Model model, String problem, String nextStep, WebConfig.Limits limits) {
        model.addAttribute("problem", problem);
        model.addAttribute("nextStep", nextStep);
        model.addAttribute("brokers", BrokerProfiles.available());
        model.addAttribute("limits", limits);
        model.addAttribute("hasSession", false);
        return "landing";
    }
}
