package com.echo.unit.guide;

import com.echo.controller.GuideCommerceController;
import com.echo.dto.Result;
import com.echo.service.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GuideControllerValidationTest {
    MockMvc mvc;GuideCommerceService service;
    @BeforeEach void setup(){service=mock(GuideCommerceService.class);mvc=MockMvcBuilders.standaloneSetup(new GuideCommerceController(service,mock(GuideActionExecutor.class),new RestTemplateBuilder())).build();}
    @Test void confirmationQuantityAndPriceAreRequired() throws Exception {
        for(String body:new String[]{"{}","{\"requestId\":\"12345678-1234-1234\",\"confirmed\":false,\"count\":1,\"expectedUnitPrice\":28000}","{\"requestId\":\"12345678-1234-1234\",\"confirmed\":true,\"count\":11,\"expectedUnitPrice\":28000}"}){
            mvc.perform(post("/guide/orders/11").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void acceptedOrderBodyPreservesStringLongIDs()throws Exception{
        when(service.order(eq(11L),any())).thenReturn(Result.ok());mvc.perform(post("/guide/orders/11").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"12345678-1234-1234\",\"confirmed\":true,\"count\":1,\"expectedUnitPrice\":\"28000\",\"seatIds\":[\"9007199254740993\"]}")).andExpect(status().isOk());verify(service).order(eq(11L),argThat(body->body.getSeatIds().get(0)==9007199254740993L));
    }
    @Test void reviewNeedsChosenScoreAndConfirmation()throws Exception{
        mvc.perform(post("/guide/reviews").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"12345678-1234-1234\",\"confirmed\":true,\"showId\":1,\"score\":0}")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
}
