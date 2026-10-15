package io.github.yibenwulong.seatflow.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

/** Suppress only the asserted negative-startup event; forward every unrelated diagnostic. */
public final class ExpectedStartupWarning {
    private ExpectedStartupWarning() {}
    public static void run(String bean,String cause,Runnable action) {
        var logger=(Logger)LoggerFactory.getLogger(AnnotationConfigApplicationContext.class);
        var parent=(Logger)LoggerFactory.getLogger("org.springframework.context.annotation");
        var captured=new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if(event.getLevel()==Level.WARN && event.getFormattedMessage().startsWith("Exception encountered during context initialization")
                        && event.getFormattedMessage().contains("name '"+bean+"'") && event.getFormattedMessage().contains(cause))
                    super.append(event);
                else parent.callAppenders(event);
            }
        };
        boolean additive=logger.isAdditive(); Level level=logger.getLevel();
        captured.start(); logger.addAppender(captured); logger.setAdditive(false);
        try {
            action.run();
            assertThat(captured.list).singleElement().satisfies(event->{
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains(cause);
            });
        } finally {
            logger.detachAppender(captured); logger.setAdditive(additive); logger.setLevel(level); captured.stop();
        }
    }
}
