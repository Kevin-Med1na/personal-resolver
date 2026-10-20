package com.redprocesal.persona_resolver.infrastructure.adapter.rues;

import com.redprocesal.persona_resolver.application.dto.CompanyBasicData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuesServiceTest {

        @Test
        void shouldParseConsultaNitResponse() {

                String json = """
                                {
                                    "page": 1,
                                    "codigo_error": "0000",
                                    "mensaje_error": "",
                                    "records": 1,
                                    "rows": [
                                        {
                                            "identificacion": "NIT 900351983 - 5",
                                            "razon_social": "RED PROCESAL S.A.S. EN LIQUIDACION",
                                            "sigla": "",
                                            "categoria_matricula": "SOCIEDAD ó PERSONA JURIDICA PRINCIPAL ó ESAL",
                                            "municipio": "CARTAGENA / BOLIVAR",
                                            "enlace": "<a>info</a>",
                                            "estadoRM": "ACTIVA"
                                        }
                                    ]
                                }
                                """;

                CompanyBasicData company = RuesService.parseConsultaNitResponse(
                                json,
                                "900351983");

                assertNotNull(company);

                assertEquals(
                                "900351983",
                                company.documentNumber());

                assertEquals(
                                "RED PROCESAL S.A.S. EN LIQUIDACION",
                                company.companyName());

                assertEquals(
                                "SOCIEDAD ó PERSONA JURIDICA PRINCIPAL ó ESAL",
                                company.businessType());

                assertEquals(
                                "ACTIVA",
                                company.state());

                assertEquals(
                                "CARTAGENA",
                                company.city());

                assertNull(
                                company.address());

                assertNull(
                                company.legalRepresentative());
        }

        @Test
        void shouldReturnNullWhenNitDoesNotExist() {

                String json = """
                                {
                                    "page": 1,
                                    "codigo_error": "0000",
                                    "mensaje_error": "",
                                    "records": 0,
                                    "rows": []
                                }
                                """;

                CompanyBasicData company = RuesService.parseConsultaNitResponse(
                                json,
                                "999999999");

                assertNull(company);
        }

        @Test
        void shouldExtractRequestVerificationToken() {

                String html = """
                                <html>
                                    <body>
                                        <form>
                                            <input type="hidden"
                                                   name="__RequestVerificationToken"
                                                   value="TOKEN-DE-PRUEBA-123">
                                        </form>
                                    </body>
                                </html>
                                """;

                String token = RuesService.extractRequestVerificationToken(
                                html);

                assertEquals(
                                "TOKEN-DE-PRUEBA-123",
                                token);
        }

        @Test
        void shouldNormalizeNitByRemovingVerificationDigit() {

                String html = """
                                <html>
                                    <body>
                                        <input type="hidden"
                                               name="__RequestVerificationToken"
                                               value="TOKEN123">
                                    </body>
                                </html>
                                """;

                String token = RuesService.extractRequestVerificationToken(
                                html);

                assertEquals(
                                "TOKEN123",
                                token);
        }

        @Test
        void shouldReturnNullWhenNitIsNotFound() {

                String json = """
                                {
                                    "page": 1,
                                    "codigo_error": "0000",
                                    "mensaje_error": "",
                                    "records": 0,
                                    "rows": []
                                }
                                """;

                CompanyBasicData company = RuesService.parseConsultaNitResponse(
                                json,
                                "999999999");

                assertNull(company);
        }

        @Test
        void shouldReturnNormalizedNitWhenInputContainsVerificationDigit() {

                String json = """
                                {
                                    "page": 1,
                                    "codigo_error": "0000",
                                    "mensaje_error": "",
                                    "records": 1,
                                    "rows": [
                                        {
                                            "identificacion": "NIT 891408584 - 5",
                                            "razon_social": "FRISBY S.A. BIC",
                                            "categoria_matricula": "SOCIEDAD ó PERSONA JURIDICA PRINCIPAL ó ESAL",
                                            "municipio": "PEREIRA / RISARALDA",
                                            "estadoRM": "ACTIVA"
                                        }
                                    ]
                                }
                                """;

                CompanyBasicData company = RuesService.parseConsultaNitResponse(
                                json,
                                "891408584-5");

                assertNotNull(company);
                assertEquals("891408584", company.documentNumber());
        }

        @Test
        void shouldReturnSameNormalizedNitWithoutVerificationDigit() {

                String json = """
                                {
                                    "page": 1,
                                    "codigo_error": "0000",
                                    "mensaje_error": "",
                                    "records": 1,
                                    "rows": [
                                        {
                                            "identificacion": "NIT 891408584 - 5",
                                            "razon_social": "FRISBY S.A. BIC",
                                            "categoria_matricula": "SOCIEDAD ó PERSONA JURIDICA PRINCIPAL ó ESAL",
                                            "municipio": "PEREIRA / RISARALDA",
                                            "estadoRM": "ACTIVA"
                                        }
                                    ]
                                }
                                """;

                CompanyBasicData company = RuesService.parseConsultaNitResponse(
                                json,
                                "891408584");

                assertNotNull(company);
                assertEquals("891408584", company.documentNumber());
        }
}