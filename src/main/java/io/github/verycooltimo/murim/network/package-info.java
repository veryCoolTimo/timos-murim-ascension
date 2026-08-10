/**
 * Сетевой слой: CustomPacketPayload, StreamCodec, регистрация через PayloadRegistrar. Обработчики payload переносят работу в главный поток; клиентская часть обработчика вызывается только из client.
 */
package io.github.verycooltimo.murim.network;
