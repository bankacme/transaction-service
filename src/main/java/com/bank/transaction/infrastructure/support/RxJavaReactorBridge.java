package com.bank.transaction.infrastructure.support;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import java.util.NoSuchElementException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Adapter at the border between the RxJava 3 ports/use cases and the Reactor types the
 * OpenAPI generator emits for the controllers (R5) — and, like account-service,
 * transaction-service also needs the Reactor -> RxJava3 direction: {@link
 * com.bank.transaction.infrastructure.adapter.out.persistence.MongoUnitOfWorkAdapter} wraps a
 * {@code Mono<T>} in Spring's {@code TransactionalOperator} (a Reactor-only API), so it has to
 * convert back to RxJava3 on the way out. Copied verbatim from account-service (only the
 * package declaration changes), which itself copied it from bank-spike (spike-report.md check
 * #1) — a generic, dependency-free bridge with nothing service-specific in it.
 *
 * <p>No extra dependency: RxJava 3's {@link Flowable} and Reactor's {@link Mono}/{@link
 * Flux} both implement {@code org.reactivestreams.Publisher}, so the conversion only needs
 * the standard Reactive Streams interface. {@link Single}, {@link Maybe} and {@link
 * Completable} are not {@code Publisher}s themselves, so they go through {@code
 * toFlowable()} first.
 */
public final class RxJavaReactorBridge {

    private RxJavaReactorBridge() {
    }

    // ---- RxJava 3 -> Reactor (use case -> controller / transaction) ----

    public static <T> Mono<T> toMono(Single<T> single) {
        return Mono.from(single.toFlowable());
    }

    public static <T> Mono<T> toMono(Maybe<T> maybe) {
        return Mono.from(maybe.toFlowable());
    }

    public static Mono<Void> toMono(Completable completable) {
        return Mono.from(completable.<Void>toFlowable());
    }

    public static <T> Flux<T> toFlux(Observable<T> observable) {
        return Flux.from(observable.toFlowable(BackpressureStrategy.BUFFER));
    }

    public static <T> Flux<T> toFlux(Flowable<T> flowable) {
        return Flux.from(flowable);
    }

    // ---- Reactor -> RxJava 3 (repository/transaction -> use case) ----

    public static <T> Single<T> toSingle(Mono<T> mono) {
        Mono<T> nonEmpty = mono.switchIfEmpty(
                Mono.error(new NoSuchElementException("Mono completed empty; cannot become a Single")));
        return Single.fromPublisher(nonEmpty);
    }

    public static <T> Maybe<T> toMaybe(Mono<T> mono) {
        return Maybe.fromPublisher(mono);
    }

    public static Completable toCompletable(Mono<Void> mono) {
        return Completable.fromPublisher(mono);
    }

    public static <T> Observable<T> toObservable(Flux<T> flux) {
        return Observable.fromPublisher(flux);
    }

    public static <T> Flowable<T> toFlowable(Flux<T> flux) {
        return Flowable.fromPublisher(flux);
    }
}
