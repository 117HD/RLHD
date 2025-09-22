package rs117.hd.utils.buffer;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GLVAOList
{
	// this needs to be larger than the largest single model
	//	private static final int VAO_SIZE = 16 * 1024 * 1024;
	private static final int VAO_SIZE = 1024 * 1024;

	private int curIdx;
	private final List<GLVAO> vaos = new ArrayList<>();

	public GLVAO get(int size)
	{
		assert size <= VAO_SIZE;

		while (curIdx < vaos.size())
		{
			GLVAO vao = vaos.get(curIdx);
			if (!vao.vbo.mapped)
			{
				vao.vbo.map();
			}

			int rem = vao.vbo.vb.remaining() * Integer.BYTES;
			if (size <= rem)
			{
				return vao;
			}

			curIdx++;
		}

		GLVAO vao = new GLVAO("");
		vao.initialize(VAO_SIZE);
		vao.vbo.map();
		vaos.add(vao);
		log.trace("Allocated VAO {}", vao.vao);
		return vao;
	}

	public List<GLVAO> unmap()
	{
		int sz = 0;
		for (GLVAO vao : vaos)
		{
			if (vao.vbo.mapped)
			{
				++sz;
				vao.vbo.unmap();
			}
		}
		curIdx = 0;
		return vaos.subList(0, sz);
	}

	public void destroy()
	{
		for (GLVAO vao : vaos)
		{
			vao.destroy();
		}
		vaos.clear();
		curIdx = 0;
	}
}