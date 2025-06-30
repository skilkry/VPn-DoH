# VentaOne DNS VPN

**License:** MIT  
**Language:** Kotlin

Cliente VPN para Android enfocado en la privacidad y seguridad, que enruta todas las consultas DNS del dispositivo a través del protocolo DNS-over-HTTPS (DoH). Incluye funcionalidades de bloqueo de anuncios, seguridad mejorada y configuración personalizada.

---

## 🛠️ Características Principales

- **VPN a Nivel de Dispositivo:** Intercepta todo el tráfico DNS mediante `VpnService` y lo procesa localmente.
- **Resolución DNS Segura (DoH):** Cifrado extremo a extremo de todas las consultas con `OkHttp` y `Dns-over-HTTPS`.
- **Bloqueo de Contenido:** Filtro local para dominios de ads, tracking y malware.
- **Soporte para Servidores Propios:** Configuración de endpoint DoH personalizado.
- **Certificate Pinning:** Verificación SHA-256 para prevenir ataques MITM.
- **UI Moderna:** Interfaz construida con Material Design, clara e intuitiva.

---

## 🔧 Tecnologías Utilizadas

### Cliente Android
- **Lenguaje:** Kotlin
- **Framework:** `VpnService`, `LocalBroadcastManager`
- **UI:** Material Components, `DrawerLayout`, `Toolbar`
- **Networking:** `OkHttp`

### Servidor DNS (referencia)
- **DNS Resolver:** BIND9
- **Proxy Inverso:** Nginx
- **Certificados:** Let's Encrypt
- **Automatización:** Bash scripting

> ⚠️ **Nota:** El script de servidor es referencial y fue desarrollado como parte del entorno de pruebas. No representa el entorno final de la empresa.

---

## 👤 Autoría y Contexto

Este proyecto fue desarrollado en su totalidad por:

**Skilkry (Daniel Sardina)**  
🔗 [github.com/skilkry](https://github.com/skilkry)

> ⚠️ **Importante:** Este trabajo fue realizado durante mis prácticas profesionales en el entorno de [VentaOne], bajo supervisión técnica. El código aquí presentado corresponde a mi versión original, publicada con fines formativos y bajo licencia abierta.

---

## 📄 Licencia

Distribuido bajo la Licencia MIT.  
Consulta el fichero `LICENSE` para más detalles.

