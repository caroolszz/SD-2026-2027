package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.List;

/**
 * Chave mestra (MK) do líder: 32 bytes aleatórios guardados em {@code master.key}.
 *
 * <ul>
 *   <li>Se o ficheiro não existir, é gerado com {@link SecureRandom} e fica legível só pelo dono.</li>
 *   <li>Se existir mas não tiver exatamente 32 bytes, o arranque é recusado
 *       ({@link IllegalStateException}): gerar outra chave tornaria ilegíveis os chunks já selados.</li>
 * </ul>
 */
public final class MasterKey {

    /** Tamanho da chave mestra em bytes (256 bits). */
    public static final int TAMANHO = 32;

    private MasterKey() {}

    /** Resultado do carregamento: a chave e se foi gerada agora (primeiro arranque). */
    public record Carregada(byte[] chave, boolean gerada) {}

    /** Lê a chave; se o ficheiro não existir, gera-a primeiro. */
    public static Carregada carregarOuGerar(Path ficheiro) throws IOException {
        if (Files.notExists(ficheiro)) {
            return new Carregada(gerar(ficheiro), true);
        }
        return new Carregada(ler(ficheiro), false);
    }

    /** Lê uma chave existente. Recusa ficheiros com tamanho diferente de 32 bytes. */
    public static byte[] ler(Path ficheiro) throws IOException {
        long tamanho = Files.size(ficheiro);
        if (tamanho != TAMANHO) {
            throw new IllegalStateException(ficheiro + " tem " + tamanho + " bytes; deviam ser " + TAMANHO
                    + ". Arranque recusado (não se gera outra chave para não perder os chunks já selados).");
        }
        byte[] chave = Files.readAllBytes(ficheiro);
        if (chave.length != TAMANHO) {   // ficheiro alterado entre size() e readAllBytes()
            throw new IllegalStateException(ficheiro + " mudou de tamanho durante a leitura.");
        }
        return chave;
    }

    private static byte[] gerar(Path ficheiro) throws IOException {
        byte[] chave = new byte[TAMANHO];
        new SecureRandom().nextBytes(chave);

        Path pasta = ficheiro.toAbsolutePath().getParent();
        if (pasta != null) Files.createDirectories(pasta);

        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (posix) {
            // Em Linux/macOS o ficheiro já nasce sem permissões para grupo e outros.
            Files.createFile(ficheiro, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(ficheiro);   // CREATE_NEW: falha se outro processo o tiver criado entretanto
        }
        try (FileChannel ch = FileChannel.open(ficheiro, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(chave);
            while (b.hasRemaining()) ch.write(b);
            ch.force(true);
        }
        restringirAoDono(ficheiro, posix);
        return chave;
    }

    /**
     * Deixa o ficheiro legível apenas pelo dono.
     * POSIX: {@code r--------} (0400). Windows: ACL com uma única entrada, para o dono,
     * com permissões de leitura (e DELETE, para se poder apagar a pasta de testes).
     */
    static void restringirAoDono(Path ficheiro, boolean posix) throws IOException {
        if (posix) {
            Files.setPosixFilePermissions(ficheiro, PosixFilePermissions.fromString("r--------"));
            return;
        }
        AclFileAttributeView vista = Files.getFileAttributeView(ficheiro, AclFileAttributeView.class);
        if (vista != null) {
            UserPrincipal dono = Files.getOwner(ficheiro);
            AclEntry entrada = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(dono)
                    .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.READ_ACL,
                            AclEntryPermission.SYNCHRONIZE, AclEntryPermission.DELETE)
                    .build();
            vista.setAcl(List.of(entrada));   // substitui a ACL inteira: mais ninguém tem acesso
        } else {
            // Último recurso (sistemas de ficheiros sem ACL nem POSIX)
            ficheiro.toFile().setReadable(false, false);
            ficheiro.toFile().setReadable(true, true);
            ficheiro.toFile().setWritable(false, false);
        }
    }
}
