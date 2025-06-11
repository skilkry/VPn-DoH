# VentaOne DNS VPN

![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)
![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)

Cliente VPN para Android enfocado en la privacidad y seguridad, que asegura todas las consultas DNS del dispositivo a través del protocolo DNS-over-HTTPS (DoH), ofreciendo además funcionalidades de bloqueo de anuncios y malware.

![image](https://github.com/user-attachments/assets/9491322d-8966-408f-a8a2-db4de341bdbb)



---

## Características Principales

* **Servicio VPN a Nivel de Dispositivo:** Enruta todo el tráfico DNS a través de un túnel VPN local para su inspección y tratamiento.
* **Resolución DNS Segura (DoH):** Cifra las consultas DNS utilizando DNS-over-HTTPS para evitar la intercepción y manipulación por parte de terceros.
* **Bloqueo de Contenido:** Filtro personalizable para bloquear dominios asociados a anuncios, rastreadores y malware.
* **Soporte de Servidores Personalizados:** Permite al usuario configurar su propio endpoint de servidor DoH.
* **Anclaje de Certificados (Certificate Pinning):** Asegura que la aplicación solo se comunique con el servidor DoH de confianza, previniendo ataques Man-in-the-Middle.
* **Interfaz de Usuario Moderna:** Construida con Material Design, intuitiva y fácil de usar.

---

## Tecnologías Utilizadas

### Aplicación Android
* **Lenguaje:** Kotlin
* **Arquitectura:** `VpnService` de Android, `LocalBroadcastManager` para comunicación UI-Servicio.
* **UI:** Material Components, `DrawerLayout`, `Toolbar`.
* **Red:** OkHttp para las peticiones DoH.

### Script de Servidor
* **Servidor DNS:** BIND9
* **Proxy Inverso:** Nginx
* **Automatización:** Script de Bash (`.sh`)

---

## Autor

Este proyecto fue diseñado y desarrollado en su totalidad por:

* **skilkry** - ([Tu Nombre Completo])
    * [GitHub](https://github.com/skilkry)
    * [LinkedIn](URL_DE_TU_PERFIL_DE_LINKEDIN) *(Opcional, pero muy recomendable)*

---

## Licencia

Este proyecto está distribuido bajo la Licencia MIT. Consulta el fichero `LICENSE` para más detalles.

Copyright (c) 2025 [Skilkry (Daniel Sardina)] & [Daniel Enriquez Cayuelas] 
